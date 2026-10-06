#define _GNU_SOURCE
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/ioctl.h>
#include <sched.h>
#include <time.h>
#include <linux/perf_event.h>
#include <errno.h>
#include <string.h>
struct reading {uint64_t count, enabled, running;};
static uint64_t ns(clockid_t clock) {struct timespec t; clock_gettime(clock,&t); return (uint64_t)t.tv_sec*1000000000+t.tv_nsec;}
static struct reading sample(int fd) {struct reading r; if(read(fd,&r,sizeof r)!=sizeof r){perror("read");exit(2);} return r;}
int main(int argc,char **argv){
 int cpu=argc>1?atoi(argv[1]):7; cpu_set_t mask; CPU_ZERO(&mask);CPU_SET(cpu,&mask);
 int tries=0; while(sched_setaffinity(0,sizeof mask,&mask)) { if(++tries>=30){fprintf(stderr,"affinity cpu%d: %s\n",cpu,strerror(errno));return 1;} volatile unsigned warm=1; uint64_t wend=ns(CLOCK_MONOTONIC_RAW)+100000000; while(ns(CLOCK_MONOTONIC_RAW)<wend)warm=warm*1664525+1013904223; }
 struct perf_event_attr attr={0}; attr.size=sizeof attr;attr.type=PERF_TYPE_HARDWARE;attr.config=PERF_COUNT_HW_CPU_CYCLES;attr.disabled=1;attr.exclude_hv=1;attr.read_format=PERF_FORMAT_TOTAL_TIME_ENABLED|PERF_FORMAT_TOTAL_TIME_RUNNING;
 int fd=syscall(__NR_perf_event_open,&attr,0,-1,-1,0);if(fd<0){perror("perf_event_open");return 2;}
 ioctl(fd,PERF_EVENT_IOC_RESET,0);ioctl(fd,PERF_EVENT_IOC_ENABLE,0);
 volatile uint64_t x=1; uint64_t finish=ns(CLOCK_MONOTONIC_RAW)+6000000000ULL;
 puts("cpu,cycles,enabled_ns,running_ns,thread_ns,wall_ns,cycles_per_thread_ns,cycles_per_wall_ns");
 while(ns(CLOCK_MONOTONIC_RAW)<finish){
  uint64_t wall_a=ns(CLOCK_MONOTONIC_RAW);struct reading a=sample(fd);uint64_t thread_a=ns(CLOCK_THREAD_CPUTIME_ID),until=wall_a+1000000;
  do {for(int i=0;i<128;i++)x=x*1664525+1013904223;}while(ns(CLOCK_MONOTONIC_RAW)<until);
  struct reading b=sample(fd);uint64_t thread_b=ns(CLOCK_THREAD_CPUTIME_ID),wall_b=ns(CLOCK_MONOTONIC_RAW);
  printf("%d,%llu,%llu,%llu,%llu,%llu,%.9f,%.9f\n",sched_getcpu(),(unsigned long long)(b.count-a.count),(unsigned long long)(b.enabled-a.enabled),(unsigned long long)(b.running-a.running),(unsigned long long)(thread_b-thread_a),(unsigned long long)(wall_b-wall_a),(double)(b.count-a.count)/(thread_b-thread_a),(double)(b.count-a.count)/(wall_b-wall_a));
 }
 ioctl(fd,PERF_EVENT_IOC_DISABLE,0);close(fd);return x==0;
}
