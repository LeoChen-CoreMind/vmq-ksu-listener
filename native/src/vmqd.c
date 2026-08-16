#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static volatile sig_atomic_t stop_requested = 0;
static volatile sig_atomic_t restart_requested = 0;
static char root_dir[512];
static char module_dir[512];
static char state_path[600];
static char desired_path[600];
static char module_prop_path[600];
static int lock_fd = -1;
static volatile sig_atomic_t child_pid = -1;
static unsigned restart_count = 0;
static long long started_at = 0;
static int published_running_state = -1;

static void on_signal(int signal_number) {
    if (signal_number == SIGTERM || signal_number == SIGINT) stop_requested = 1;
    if (signal_number == SIGHUP) restart_requested = 1;
    if (child_pid > 0) kill((pid_t)child_pid, SIGTERM);
}

static void fail(const char *format, ...) {
    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
    fputc('\n', stderr);
}

static int ensure_directory(const char *path) {
    char buffer[sizeof(root_dir)];
    size_t length = strlen(path);
    if (length >= sizeof(buffer)) return -1;
    memcpy(buffer, path, length + 1);
    for (char *cursor = buffer + 1; *cursor; cursor++) {
        if (*cursor == '/') {
            *cursor = '\0';
            if (mkdir(buffer, 0700) == -1 && errno != EEXIST) return -1;
            *cursor = '/';
        }
    }
    if (mkdir(buffer, 0700) == -1 && errno != EEXIST) return -1;
    return 0;
}

static void sleep_ms(long long milliseconds) {
    struct timespec request;
    request.tv_sec = milliseconds / 1000;
    request.tv_nsec = (milliseconds % 1000) * 1000000L;
    while (nanosleep(&request, &request) == -1 && errno == EINTR && !stop_requested) { }
}

static int read_text(const char *path, char *buffer, size_t capacity) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd == -1) return -1;
    size_t used = 0;
    while (used < capacity - 1) {
        ssize_t count = read(fd, buffer + used, capacity - 1 - used);
        if (count == 0) break;
        if (count < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        used += (size_t)count;
    }
    char extra;
    ssize_t extra_count;
    do {
        extra_count = read(fd, &extra, 1);
    } while (extra_count < 0 && errno == EINTR);
    close(fd);
    if (extra_count != 0) return -1;
    buffer[used] = '\0';
    return (int)used;
}

static int desired_running(void) {
    char value[32];
    int count = read_text(desired_path, value, sizeof(value));
    if (count <= 0) return 1;
    return strstr(value, "stopped") == NULL;
}

static int replace_file(const char *path, const char *contents) {
    char temporary[sizeof(state_path) + 32];
    snprintf(temporary, sizeof(temporary), "%s.tmp.%ld", path, (long)getpid());
    int fd = open(temporary, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd == -1) return -1;
    size_t length = strlen(contents);
    ssize_t written = write(fd, contents, length);
    int result = (written == (ssize_t)length && fsync(fd) == 0) ? 0 : -1;
    close(fd);
    if (result == 0 && rename(temporary, path) == -1) result = -1;
    if (result != 0) unlink(temporary);
    return result;
}

static int set_record_lock(int fd, short type, int command) {
    struct flock lock;
    memset(&lock, 0, sizeof(lock));
    lock.l_type = type;
    lock.l_whence = SEEK_SET;
    int result;
    do {
        result = fcntl(fd, command, &lock);
    } while (result == -1 && errno == EINTR);
    return result;
}

static int append_text(char *output, size_t capacity, size_t *used, const char *value, size_t length) {
    if (*used + length >= capacity) return -1;
    memcpy(output + *used, value, length);
    *used += length;
    output[*used] = '\0';
    return 0;
}

static int render_module_property(const char *current, const char *description, char *output, size_t capacity) {
    const char *cursor = current;
    size_t used = 0;
    bool found = false;
    output[0] = '\0';
    while (*cursor) {
        const char *newline = strchr(cursor, '\n');
        size_t length = newline ? (size_t)(newline - cursor) : strlen(cursor);
        if (length >= 12 && strncmp(cursor, "description=", 12) == 0) {
            if (append_text(output, capacity, &used, description, strlen(description)) != 0) return -1;
            found = true;
        } else if (append_text(output, capacity, &used, cursor, length) != 0) {
            return -1;
        }
        if (newline && append_text(output, capacity, &used, "\n", 1) != 0) return -1;
        if (!newline) break;
        cursor = newline + 1;
    }
    if (!found) {
        if (used > 0 && output[used - 1] != '\n' && append_text(output, capacity, &used, "\n", 1) != 0) return -1;
        if (append_text(output, capacity, &used, description, strlen(description)) != 0) return -1;
        if (append_text(output, capacity, &used, "\n", 1) != 0) return -1;
    }
    return 0;
}

static int update_module_description(bool running) {
    const char *description = running
            ? "description=V免签通知监听服务正在运行"
            : "description=V免签通知监听服务已停止运行";
    char current[4096];
    if (read_text(module_prop_path, current, sizeof(current)) < 0) return -1;
    char output[4096];
    if (render_module_property(current, description, output, sizeof(output)) != 0) return -1;
    if (strcmp(current, output) == 0) return 0;

    char lock_path[sizeof(module_prop_path) + 16];
    snprintf(lock_path, sizeof(lock_path), "%s.lock", module_prop_path);
    int metadata_lock = open(lock_path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (metadata_lock == -1 || set_record_lock(metadata_lock, F_WRLCK, F_SETLKW) == -1) {
        if (metadata_lock >= 0) close(metadata_lock);
        return -1;
    }
    int result = read_text(module_prop_path, current, sizeof(current)) < 0
            || render_module_property(current, description, output, sizeof(output)) != 0
            ? -1 : (strcmp(current, output) == 0 ? 0 : replace_file(module_prop_path, output));
    if (result == 0) chmod(module_prop_path, 0644);
    set_record_lock(metadata_lock, F_UNLCK, F_SETLK);
    close(metadata_lock);
    return result;
}

static void publish_running_state(bool running) {
    if (published_running_state == (running ? 1 : 0)) return;
    if (update_module_description(running) == 0) {
        published_running_state = running ? 1 : 0;
    } else {
        fail("VMQ_MODULE_METADATA_UPDATE_FAILED 模块状态说明更新失败");
    }
}

static int find_json_object_bounds(char *text, char **object_start, char **object_end) {
    char *key = strstr(text, "\"supervisor\"");
    if (!key) return 0;
    char *start = strchr(key, '{');
    if (!start) return 0;
    int depth = 0;
    bool quoted = false;
    bool escaped = false;
    for (char *cursor = start; *cursor; cursor++) {
        if (quoted) {
            if (escaped) escaped = false;
            else if (*cursor == '\\') escaped = true;
            else if (*cursor == '"') quoted = false;
            continue;
        }
        if (*cursor == '"') quoted = true;
        else if (*cursor == '{') depth++;
        else if (*cursor == '}' && --depth == 0) {
            *object_start = start;
            *object_end = cursor + 1;
            return 1;
        }
    }
    return 0;
}

static int write_state(const char *desired_state) {
    char lock_path[sizeof(state_path) + 16];
    snprintf(lock_path, sizeof(lock_path), "%s.lock", state_path);
    int state_lock = open(lock_path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (state_lock == -1 || set_record_lock(state_lock, F_WRLCK, F_SETLKW) == -1) {
        if (state_lock >= 0) close(state_lock);
        return -1;
    }
    char current[8192];
    int count = read_text(state_path, current, sizeof(current));
    if (count < 0) strcpy(current, "{}");
    char supervisor[512];
    long supervisor_pid = (stop_requested && strcmp(desired_state, "stopped") == 0) ? -1L : (long)getpid();
    snprintf(supervisor, sizeof(supervisor),
             "{\"pid\":%ld,\"listenerPid\":%ld,\"startedAt\":%lld,\"restartCount\":%u,\"desiredState\":\"%s\"}",
             supervisor_pid, (long)child_pid, started_at, restart_count, desired_state);
    char output[sizeof(current) + sizeof(supervisor) + 32];
    char *start = NULL;
    char *end = NULL;
    if (find_json_object_bounds(current, &start, &end)) {
        size_t prefix = (size_t)(start - current);
        size_t suffix = strlen(end);
        memcpy(output, current, prefix);
        memcpy(output + prefix, supervisor, strlen(supervisor));
        memcpy(output + prefix + strlen(supervisor), end, suffix + 1);
    } else {
        size_t length = strlen(current);
        while (length > 0 && (current[length - 1] == ' ' || current[length - 1] == '\n' || current[length - 1] == '\r' || current[length - 1] == '\t')) length--;
        if (length == 0 || current[length - 1] != '}') {
            set_record_lock(state_lock, F_UNLCK, F_SETLK);
            close(state_lock);
            return -1;
        }
        current[length - 1] = '\0';
        snprintf(output, sizeof(output), "%s%s%s", current, length > 2 ? "," : "", "\"supervisor\":{}");
        char *placeholder = strstr(output, "\"supervisor\":{}");
        if (!placeholder) {
            set_record_lock(state_lock, F_UNLCK, F_SETLK);
            close(state_lock);
            return -1;
        }
        snprintf(placeholder, sizeof(output) - (size_t)(placeholder - output), "\"supervisor\":%s}", supervisor);
    }
    int result = replace_file(state_path, output);
    set_record_lock(state_lock, F_UNLCK, F_SETLK);
    close(state_lock);
    return result;
}

static pid_t spawn_listener(void) {
    pid_t expected_parent = getpid();
    pid_t pid = fork();
    if (pid != 0) return pid;
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) == -1 || getppid() != expected_parent) _exit(126);
    char classpath[sizeof(module_dir) + 32];
    snprintf(classpath, sizeof(classpath), "%s/dex/vmq-listener.dex", module_dir);
    char root_arg[sizeof(root_dir) + 16];
    snprintf(root_arg, sizeof(root_arg), "%s", root_dir);
    setenv("CLASSPATH", classpath, 1);
    execl("/system/bin/app_process64", "app_process64", "/system/bin",
          "com.vmq.ksu.Main", "--root", root_arg, (char *)NULL);
    _exit(127);
}

static void terminate_child(pid_t pid, int *status) {
    if (pid <= 0) return;
    kill(pid, SIGTERM);
    for (int attempt = 0; attempt < 50; attempt++) {
        pid_t waited = waitpid(pid, status, WNOHANG);
        if (waited == pid || (waited == -1 && errno == ECHILD)) return;
        if (waited == -1 && errno != EINTR) break;
        sleep_ms(100);
    }
    kill(pid, SIGKILL);
    while (waitpid(pid, status, 0) == -1 && errno == EINTR) { }
}

static int run_supervisor(void) {
    if (ensure_directory(root_dir) != 0) return 1;
    snprintf(state_path, sizeof(state_path), "%s/state.json", root_dir);
    snprintf(desired_path, sizeof(desired_path), "%s/desired_state", root_dir);
    snprintf(module_prop_path, sizeof(module_prop_path), "%s/module.prop", module_dir);
    char lock_path[sizeof(root_dir) + 32];
    snprintf(lock_path, sizeof(lock_path), "%s/vmqd.lock", root_dir);
    lock_fd = open(lock_path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
    if (lock_fd == -1 || flock(lock_fd, LOCK_EX | LOCK_NB) == -1) {
        fail("vmqd: 已有服务守护进程正在运行");
        return 2;
    }
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);
    signal(SIGPIPE, SIG_IGN);
    started_at = (long long)time(NULL) * 1000LL;
    write_state(desired_running() ? "running" : "stopped");

    const int delays[] = {1, 2, 5, 15, 30};
    unsigned delay_index = 0;
    while (!stop_requested) {
        if (!desired_running()) {
            child_pid = -1;
            write_state("stopped");
            publish_running_state(false);
            sleep_ms(500);
            continue;
        }
        child_pid = spawn_listener();
        if (child_pid < 0) {
            fail("vmqd: 创建监听进程失败: %s", strerror(errno));
            sleep_ms(1000);
            continue;
        }
        write_state("running");
        publish_running_state(true);
        int status = 0;
        pid_t waited;
        do {
            waited = waitpid((pid_t)child_pid, &status, 0);
        } while (waited == -1 && errno == EINTR && !stop_requested && !restart_requested);
        if ((stop_requested || restart_requested) && child_pid > 0) {
            terminate_child((pid_t)child_pid, &status);
        }
        child_pid = -1;
        write_state(stop_requested ? "stopped" : "running");
        if (stop_requested) break;
        if (restart_requested) {
            restart_requested = 0;
            delay_index = 0;
        }
        sleep_ms((long long)delays[delay_index] * 1000LL);
        if (delay_index < sizeof(delays) / sizeof(delays[0]) - 1) delay_index++;
        restart_count++;
    }
    if (child_pid > 0) {
        int status = 0;
        terminate_child((pid_t)child_pid, &status);
        child_pid = -1;
    }
    write_state("stopped");
    publish_running_state(false);
    if (lock_fd >= 0) close(lock_fd);
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) {
        puts("VMQD_SELF_TEST_READY");
        return 0;
    }
    if (argc != 5 || strcmp(argv[1], "--root") != 0 || strcmp(argv[3], "--module") != 0) {
        fprintf(stderr, "用法: vmqd --root DIR --module DIR\n");
        return 64;
    }
    if (strlen(argv[2]) >= sizeof(root_dir) || strlen(argv[4]) >= sizeof(module_dir)) return 64;
    strcpy(root_dir, argv[2]);
    strcpy(module_dir, argv[4]);
    return run_supervisor();
}
