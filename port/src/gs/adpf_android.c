/*
 * Android Dynamic Performance Framework (ADPF): the game thread tells the system how long each tick's work took and
 * how long it may take, so that the CPU governor raises the clocks before a tick runs late instead of after (a
 * governor that only sees load ramps up a few ticks too late: a short stutter as a fight's effects begin), and lowers
 * them when there is room (less heat over a long session, which is what throttles a phone or tablet later on).
 *
 * The NDK's APerformanceHint (Android 13, API 33), looked up in libandroid.so at run time: the engine is built for
 * Android 10 (API 29) and must still load where the functions do not exist. The session holds one thread: the game's
 * own (plat_mem.c: game_thread), which computes the tick, walks its display list AND presents it (gs_core.c:
 * run_chain -> GsGpu_FrameEnd); no other thread of the engine does per-frame work unless BT3_GS_THREAD=1.
 *
 * The time reported is gs_core.c's "work": a tick from the start of one to the start of the next, less what was spent
 * waiting for the vertical blanks. The target is the tick's length: two blanks in a fight (33.4 ms at the game's pace,
 * less in an online match at 120%), one in the menus. setPreferPowerEfficiency (API 34+) is left off on purpose: it
 * tells the system this work may run slower, and a fight's tick must not.
 *
 * Nothing here touches the game's state (timing only): a recording and an online match run the same.
 * Setting dr_adpf (1 on, the default; 0 off).
 */
#ifdef __ANDROID__
#include <android/api-level.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

extern int Port_Setting(const char *name, int def);   /* plat_settings.c */
extern unsigned long long Port_BlankNs(void);         /* plat_stub.c: 16.68 ms, less online at 120% */
extern int gPortInMenus;                              /* headless.c: the menus run (a tick of one blank) */
extern int gPortResim;                                /* gs/state.c: a tick run again after a rollback (not timed) */

typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;

static APerformanceHintManager *(*pGetManager)(void);
static APerformanceHintSession *(*pCreateSession)(APerformanceHintManager *, const int32_t *, size_t, int64_t);
static int (*pUpdateTarget)(APerformanceHintSession *, int64_t);
static int (*pReport)(APerformanceHintSession *, int64_t);

static APerformanceHintSession *sSession;
static int sState;       /* 0 not tried yet, 1 a session, -1 none (off, too old, refused) */
static int64_t sTarget;

/* Made on the first tick, on the game thread itself (its id is the caller's). */
static void adpf_open(int64_t target) {
    void *lib;
    int api = android_get_device_api_level();
    int32_t tid = (int32_t)gettid();

    sState = -1;
    if (Port_Setting("dr_adpf", 1) == 0) {
        fprintf(stderr, "bt3: ADPF: off (dr_adpf 0)\n");
        return;
    }
    if (api < 33) {
        fprintf(stderr, "bt3: ADPF: not available (Android API %d; it needs 33)\n", api);
        return;
    }
    lib = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (lib == NULL) {
        lib = dlopen("libandroid.so", RTLD_NOW);
    }
    if (lib != NULL) {
        pGetManager = (APerformanceHintManager * (*)(void)) dlsym(lib, "APerformanceHint_getManager");
        pCreateSession = (APerformanceHintSession * (*)(APerformanceHintManager *, const int32_t *, size_t, int64_t))
            dlsym(lib, "APerformanceHint_createSession");
        pUpdateTarget = (int (*)(APerformanceHintSession *, int64_t))dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
        pReport = (int (*)(APerformanceHintSession *, int64_t))dlsym(lib, "APerformanceHint_reportActualWorkDuration");
    }
    if (pGetManager == NULL || pCreateSession == NULL || pUpdateTarget == NULL || pReport == NULL) {
        fprintf(stderr, "bt3: ADPF: libandroid.so has no APerformanceHint functions\n");
        return;
    }
    {
        APerformanceHintManager *m = pGetManager();
        sSession = m != NULL ? pCreateSession(m, &tid, 1, target) : NULL;
    }
    if (sSession == NULL) {
        /* (a device whose system does not support the hints: the manager or the session is refused) */
        fprintf(stderr, "bt3: ADPF: the system refused a session (no performance hint support on this device)\n");
        return;
    }
    sState = 1;
    sTarget = target;
    fprintf(stderr, "bt3: ADPF: session created for the game thread (tid %d), target %.1f ms\n", (int)tid, (double)target / 1e6);
}

/* One tick's work in nanoseconds (gs_core.c, once per tick, on the game thread). */
void PortAdpf_Report(unsigned long long workNs) {
    int64_t target = (int64_t)(Port_BlankNs() * (gPortInMenus ? 1ull : 2ull));

    if (sState < 0 || gPortResim) {
        return;
    }
    if (sState == 0) {
        adpf_open(target);
        if (sState < 0) {
            return;
        }
    }
    /* A tick that held a load, a pause (the in-game menu, the app in the background) or a stall is no measure of
       the work a tick takes: told as one, it would leave the clocks high for no reason. */
    if (workNs == 0 || workNs > 200000000ull) {
        return;
    }
    if (target != sTarget) { /* fight <-> menus, an online match's speed */
        if (pUpdateTarget(sSession, target) == 0) {
            sTarget = target;
        }
    }
    pReport(sSession, (int64_t)workNs);
}
#else
typedef int PortAdpfUnused; /* (other systems: nothing; an empty file is not valid C) */
#endif
