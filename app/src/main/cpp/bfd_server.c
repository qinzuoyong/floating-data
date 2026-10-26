/*
 * BatteryFloating 内置特权服务(bfd_server)
 *
 * 借鉴 Shizuku starter/server 的存活机制(fork+setsid 守护化,脱离 adbd 会话),
 * 但不加载 JVM:一个纯 C 守护进程,监听 127.0.0.1 TCP,代本应用执行 shell 命令。
 * 由 ADB 无线调试以 shell(uid=2000)身份拉起一次,之后无线调试开关状态无关,
 * 直到手机重启。
 *
 * 监听通道选择:抽象 Unix socket 会被 SELinux 拦截(untrusted_app 无法 connectto
 * shell 域 socket),改用环回 TCP——应用连接 localhost TCP 属普遍放行路径。
 *
 * 安全边界:
 * - 仅绑定 127.0.0.1,局域网不可达
 * - 令牌保密:拉起时经 --token=<hex> 传入随机令牌;Android hidepid=2 下其他应用
 *   读不到本进程 cmdline,故令牌本身不外泄
 * - **端口保密(2026-10 加固)**:端口由内核随机分配(port=0),绑定后立即把
 *   `PORT=<n>` 打印到 starter 的 stdout,客户端据此直连。旧实现固定 41900 起、
 *   被占则顺延,而客户端从 41900 递增探测且"连上即发令牌"——本机任意应用只要
 *   抢在 daemon 前 bind 41900,就能在探测第一站拿到令牌,再持令牌连真实端口
 *   以 uid=2000 执行任意命令。改为随机端口 + 精确直连后,恶意应用既猜不到端口,
 *   也不会被客户端误连,令牌不再外流。
 *   (残留风险:若 daemon 已死且恶意应用恰好占住同一随机端口,仍可能收到令牌。
 *    彻底解决需挑战-应答,但那要求 HMAC-SHA256,与本文件"纯 C 不链加密库"的
 *    体积约束冲突,故此处只做端口保密。)
 * - 单连接单命令,长度上限约束
 *
 * 协议(每连接):4字节小端令牌长 + 令牌 + 4字节小端命令长 + 命令 →
 * 4字节小端输出长 + 输出。特殊命令:"ping"→"pong";"shutdown" 退出;
 * "trust-key <adb 公钥行>" 追加本应用 ADB 公钥到 /data/misc/adb/adb_keys
 * (参数即 adb_keys 的行内容「<base64 公钥> <name>」,不再做额外编码)。
 */
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define SERVER_NAME "bfd_server"
/* 默认端口 0 = 由内核随机分配。**不要改回固定端口**:客户端按 PORT= 精确直连,
 * 固定端口会让"连上即发令牌"落到抢先占用该端口的本机应用手里(见文件头安全边界)。
 * 需要固定端口的调试场景用 --port=<n> 显式指定。 */
#define DEFAULT_PORT 0
#define MAX_TOKEN_LEN 128
#define MAX_CMD_LEN (32 * 1024)
#define MAX_OUT_LEN (256 * 1024)
#define CMD_TIMEOUT_SEC 15
/* 连接级读写超时(秒)。守护进程是单线程 accept 循环,任何一次阻塞读没有
 * 超时都会把整个 daemon 卡死:连上却不发数据的客户端(应用进程被杀留下的
 * 半开连接、本机端口扫描、其他应用的探测)会让 read_full 永久阻塞,此后
 * 所有 exec 都只能等到 15 秒超时,特权通道整体失效,只有重启手机才能恢复。 */
#define CONN_TIMEOUT_SEC 5
#define ADB_KEYS_PATH "/data/misc/adb/adb_keys"

static char g_token[MAX_TOKEN_LEN];

static int write_all(int fd, const void *buf, size_t len) {
    const char *p = (const char *) buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        len -= (size_t) n;
    }
    return 0;
}

static int read_full(int fd, void *buf, size_t len) {
    char *p = (char *) buf;
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (n == 0) return -1; /* 对端提前关闭 */
        p += n;
        len -= (size_t) n;
    }
    return 0;
}

/* 杀掉旧实例:扫描 /proc 的 cmdline 含 libbfd.so 者(comm 因厂商 ROM 表现不一,
 * 不作为依据;cmdline 由 fork+exec 保持,跨安装路径变更也命中) */
static int kill_old_instances(void) {
    DIR *d = opendir("/proc");
    if (!d) return 0;
    struct dirent *e;
    int killed = 0;
    while ((e = readdir(d)) != NULL) {
        pid_t pid = atoi(e->d_name);
        if (pid <= 0 || pid == getpid() || pid == getppid()) continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char cmd[512];
        ssize_t n = read(fd, cmd, sizeof(cmd) - 1);
        close(fd);
        if (n <= 0) continue;
        cmd[n] = 0;
        for (ssize_t i = 0; i < n; i++) {
            if (cmd[i] == 0) cmd[i] = ' ';
        }
        if (strstr(cmd, "libbfd.so") != NULL && kill(pid, SIGKILL) == 0) killed++;
    }
    closedir(d);
    return killed;
}

/* 执行命令并捕获输出(合并 stderr);超时杀子进程。返回 0 成功 */
static int run_command(const char *cmd, char *out, size_t outcap, size_t *outlen) {
    int pfd[2];
    if (pipe(pfd) != 0) return -1;
    pid_t pid = fork();
    if (pid < 0) {
        close(pfd[0]);
        close(pfd[1]);
        return -1;
    }
    if (pid == 0) {
        close(pfd[0]);
        dup2(pfd[1], STDOUT_FILENO);
        dup2(pfd[1], STDERR_FILENO);
        close(pfd[1]);
        execl("/system/bin/sh", "sh", "-c", cmd, (char *) NULL);
        _exit(127);
    }
    close(pfd[1]);

    *outlen = 0;
    time_t deadline = time(NULL) + CMD_TIMEOUT_SEC;
    for (;;) {
        struct timeval tv;
        tv.tv_sec = 1;
        tv.tv_usec = 0;
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(pfd[0], &rfds);
        if (select(pfd[0] + 1, &rfds, NULL, NULL, &tv) <= 0) {
            if (time(NULL) >= deadline) {
                kill(pid, SIGKILL);
                waitpid(pid, NULL, 0);
                close(pfd[0]);
                return -2; /* 超时 */
            }
            continue;
        }
        char chunk[4096];
        ssize_t n = read(pfd[0], chunk, sizeof(chunk));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (n == 0) break;
        size_t room = outcap - *outlen;
        size_t take = (size_t) n < room ? (size_t) n : room;
        memcpy(out + *outlen, chunk, take);
        *outlen += take;
        if (*outlen >= outcap) break;
    }
    close(pfd[0]);

    int status = 0;
    for (;;) {
        pid_t w = waitpid(pid, &status, 0);
        if (w == pid) break;
        if (w < 0 && errno == EINTR) continue;
        if (w < 0) break;
    }
    return 0;
}

/* 追加本应用 ADB 公钥到 adb_keys(经典 adbd 信任);已存在则跳过。
 * 注意:文件存在 != 已受信——必须逐行比对后再决定是否追加。
 * 旧实现只要文件存在就只查不写,而 adb_keys 在开启过 USB 调试的设备上几乎必然存在,
 * 导致自愈基座的密钥受信步骤长期静默失效。 */
static void handle_trust_key(int conn, const char *arg) {
    char resp[256];
    if (arg == NULL || arg[0] == 0) {
        snprintf(resp, sizeof(resp), "empty-arg");
    } else {
        int exists = 0;
        FILE *f = fopen(ADB_KEYS_PATH, "r");
        if (f) {
            char line[2048];
            size_t arg_len = strlen(arg);
            while (fgets(line, sizeof(line), f)) {
                if (strncmp(line, arg, arg_len) == 0) {
                    exists = 1;
                    break;
                }
            }
            fclose(f);
        }
        if (exists) {
            strcpy(resp, "already-trusted");
        } else {
            FILE *o = fopen(ADB_KEYS_PATH, "a");
            if (!o) {
                snprintf(resp, sizeof(resp), "open-failed errno=%d", errno);
            } else {
                /* 每行以换行结尾,避免与后写入的公钥粘连成非法行 */
                fprintf(o, "%s\n", arg);
                fclose(o);
                strcpy(resp, "trusted");
            }
        }
    }
    unsigned int rlen = (unsigned int) strlen(resp);
    if (write_all(conn, &rlen, sizeof(rlen)) == 0) write_all(conn, resp, rlen);
}

static void handle_connection(int conn) {
    /* 先给连接装读写超时,再做任何阻塞读。缺了这一步,一个只连接不发数据的
     * 客户端就能让单线程的 accept 循环永久卡在本函数内(见 CONN_TIMEOUT_SEC)。 */
    struct timeval tv;
    tv.tv_sec = CONN_TIMEOUT_SEC;
    tv.tv_usec = 0;
    setsockopt(conn, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(conn, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    /* 令牌认证 */
    unsigned int len = 0;
    if (read_full(conn, &len, sizeof(len)) != 0 || len == 0 || len > MAX_TOKEN_LEN) {
        close(conn);
        return;
    }
    char token[MAX_TOKEN_LEN + 1];
    if (read_full(conn, token, len) != 0) {
        close(conn);
        return;
    }
    token[len] = 0;
    if (strcmp(token, g_token) != 0) {
        close(conn); /* 令牌不匹配,静默拒绝 */
        return;
    }

    if (read_full(conn, &len, sizeof(len)) != 0 || len == 0 || len > MAX_CMD_LEN) {
        close(conn);
        return;
    }
    char *cmd = (char *) malloc(len + 1);
    if (!cmd) {
        close(conn);
        return;
    }
    if (read_full(conn, cmd, len) != 0) {
        free(cmd);
        close(conn);
        return;
    }
    cmd[len] = 0;

    static char out[MAX_OUT_LEN];
    size_t outlen = 0;
    if (strcmp(cmd, "ping") == 0) {
        outlen = 4;
        memcpy(out, "pong", 4);
    } else if (strcmp(cmd, "shutdown") == 0) {
        free(cmd);
        close(conn);
        exit(0);
    } else if (strncmp(cmd, "trust-key ", 10) == 0) {
        handle_trust_key(conn, cmd + 10);
        free(cmd);
        close(conn);
        return;
    } else {
        if (run_command(cmd, out, sizeof(out), &outlen) != 0 && outlen == 0) {
            const char *msg = "run-command-failed";
            memcpy(out, msg, strlen(msg));
            outlen = strlen(msg);
        }
    }
    free(cmd);

    unsigned int rlen = (unsigned int) outlen;
    if (write_all(conn, &rlen, sizeof(rlen)) == 0 && rlen > 0) {
        write_all(conn, out, rlen);
    }
    close(conn);
}

int main(int argc, char *argv[]) {
    int port = DEFAULT_PORT;
    int daemonize = 1;
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "--token=", 8) == 0) {
            snprintf(g_token, sizeof(g_token), "%s", argv[i] + 8);
        } else if (strncmp(argv[i], "--port=", 7) == 0) {
            port = atoi(argv[i] + 7);
        } else if (strcmp(argv[i], "--foreground") == 0) {
            daemonize = 0; /* 联调/诊断用 */
        }
    }
    if (g_token[0] == 0) {
        fprintf(stderr, "fatal: missing --token\n");
        return 2;
    }
    if (getuid() != 2000) {
        fprintf(stderr, "fatal: run as non shell user (uid=%d)\n", getuid());
        return 3;
    }

    kill_old_instances();

    /* 先绑定再守护化。两个理由:
     * 1) 端口必须由父进程打印给调用方(fork 后 stdout 已被重定向到 /dev/null);
     * 2) 客户端靠这行 PORT= 得知端口,不再遍历猜测——遍历会让"连上即发令牌"
     *    落到抢占端口的恶意应用手里(见文件头安全边界说明)。 */
    int sfd = socket(AF_INET, SOCK_STREAM, 0);
    if (sfd < 0) return 5;
    int reuse = 1;
    setsockopt(sfd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); /* 仅本机可达 */
    addr.sin_port = htons((unsigned short) port);   /* port=0 时由内核随机分配 */
    if (bind(sfd, (struct sockaddr *) &addr, sizeof(addr)) != 0) {
        fprintf(stderr, "fatal: bind failed errno=%d\n", errno);
        close(sfd);
        return 6;
    }
    socklen_t addrLen = sizeof(addr);
    if (getsockname(sfd, (struct sockaddr *) &addr, &addrLen) != 0) {
        fprintf(stderr, "fatal: getsockname failed errno=%d\n", errno);
        close(sfd);
        return 6;
    }
    port = ntohs(addr.sin_port);
    /* 客户端唯一可靠的端口来源,不可删除。 */
    printf("PORT=%d\n", port);
    fflush(stdout);

    if (daemonize) {
        pid_t pid = fork();
        if (pid < 0) {
            close(sfd);
            return 4;
        }
        if (pid > 0) {
            printf("info: bfd_server pid is %d\n", pid);
            fflush(stdout);
            return 0;
        }
        setsid();
        chdir("/");
        int nul = open("/dev/null", O_RDWR);
        if (nul != -1) {
            dup2(nul, STDIN_FILENO);
            dup2(nul, STDOUT_FILENO);
            dup2(nul, STDERR_FILENO);
            if (nul > 2) close(nul);
        }
    }
    prctl(PR_SET_NAME, SERVER_NAME, 0, 0, 0);
    signal(SIGPIPE, SIG_IGN);

    if (listen(sfd, 4) != 0) {
        close(sfd);
        return 6;
    }

    for (;;) {
        int conn = accept(sfd, NULL, NULL);
        if (conn < 0) {
            if (errno == EINTR) continue;
            return 7;
        }
        handle_connection(conn);
    }
}
