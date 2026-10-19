/*
 * bionic_pthread_compat.c — bionic pthread ABI surface for libb.so
 *
 * HAPs and Android-built support libraries (e.g. libarkui_android.so,
 * NDK libc++_shared.so) may reference bionic's pthread extensions.  When
 * those objects are loaded into a HAP process they resolve against
 * libb.so first (it sits in the same linker namespace), so the *np /
 * clockwait entry points must exist here as well.
 *
 * Forwarding implementations: the monotonic/clock variants convert the
 * absolute timeout to CLOCK_REALTIME and call the realtime variants
 * exported by the musl build in this same library.
 */

#include <pthread.h>
#include <time.h>
#include <unistd.h>
#include <errno.h>

static int monotonic_to_realtime(const struct timespec *mono, struct timespec *rt)
{
    struct timespec now_mono, now_real;
    if (clock_gettime(CLOCK_MONOTONIC, &now_mono) != 0) return -1;
    if (clock_gettime(CLOCK_REALTIME, &now_real) != 0) return -1;
    long long delta = (long long)now_real.tv_sec - (long long)now_mono.tv_sec;
    long long nsec  = (long long)now_real.tv_nsec - (long long)now_mono.tv_nsec;
    if (nsec < 0) { nsec += 1000000000LL; delta -= 1; }
    rt->tv_sec  = mono->tv_sec + (time_t)delta;
    rt->tv_nsec = mono->tv_nsec + (long)nsec;
    if (rt->tv_nsec >= 1000000000L) { rt->tv_nsec -= 1000000000L; rt->tv_sec += 1; }
    return 0;
}

int pthread_cond_timedwait_monotonic_np(pthread_cond_t *cond,
                                        pthread_mutex_t *mutex,
                                        const struct timespec *timeout)
{
    struct timespec rt;
    if (!timeout) return pthread_cond_timedwait(cond, mutex, NULL);
    if (monotonic_to_realtime(timeout, &rt) != 0) return EINVAL;
    return pthread_cond_timedwait(cond, mutex, &rt);
}

int pthread_mutex_timedlock_monotonic_np(pthread_mutex_t *mutex,
                                         const struct timespec *timeout)
{
    struct timespec rt;
    if (!timeout) return pthread_mutex_timedlock(mutex, NULL);
    if (monotonic_to_realtime(timeout, &rt) != 0) return EINVAL;
    return pthread_mutex_timedlock(mutex, &rt);
}

int pthread_rwlock_timedrdlock_monotonic_np(pthread_rwlock_t *rwlock,
                                            const struct timespec *timeout)
{
    struct timespec rt;
    if (!timeout) return pthread_rwlock_timedrdlock(rwlock, NULL);
    if (monotonic_to_realtime(timeout, &rt) != 0) return EINVAL;
    return pthread_rwlock_timedrdlock(rwlock, &rt);
}

int pthread_rwlock_timedwrlock_monotonic_np(pthread_rwlock_t *rwlock,
                                            const struct timespec *timeout)
{
    struct timespec rt;
    if (!timeout) return pthread_rwlock_timedwrlock(rwlock, NULL);
    if (monotonic_to_realtime(timeout, &rt) != 0) return EINVAL;
    return pthread_rwlock_timedwrlock(rwlock, &rt);
}

int pthread_cond_clockwait(pthread_cond_t *cond, pthread_mutex_t *mutex,
                           clockid_t clock, const struct timespec *timeout)
{
    if (clock == CLOCK_REALTIME || !timeout)
        return pthread_cond_timedwait(cond, mutex, timeout);
    struct timespec rt;
    if (clock == CLOCK_MONOTONIC) {
        if (monotonic_to_realtime(timeout, &rt) != 0) return EINVAL;
        return pthread_cond_timedwait(cond, mutex, &rt);
    }
    /* Any other clock: best effort, treat as realtime. */
    return pthread_cond_timedwait(cond, mutex, timeout);
}

pid_t pthread_gettid_np(pthread_t t)
{
    (void)t;
    return gettid();
}
