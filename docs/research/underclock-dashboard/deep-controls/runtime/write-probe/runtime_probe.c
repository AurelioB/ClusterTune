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
#include <pthread.h>
#include <stdatomic.h>
#include <fcntl.h>
struct reading {uint64_t count,enabled,running;};
static atomic_int done=0;
static uint64_t ns(clockid_t clock) {struct timespec t;if(clock_gettime(clock,&t)){perror("clock_gettime");exit(2);}return (uint64_t)t.tv_sec*1000000000+t.tv_nsec;}
static struct reading sample(int fd) {struct reading r;if(read(fd,&r,sizeof r)!=sizeof r){perror("perf read");exit(2);}return r;}
static int affinity(int cpu) {cpu_set_t mask;CPU_ZERO(&mask);CPU_SET(cpu,&mask);return sched_setaffinity(0,sizeof mask,&mask);}
static void *observe(void *unused) {
 (void)unused;
 if(affinity(0)) {perror("observer affinity");return (void*)1;}
 int fd=open("/sys/kernel/debug/clk/measure_only_apcs_goldplus_post_acd_clk/clk_measure",O_RDONLY);
 if(fd<0) {perror("hardware clock open");return (void*)1;}
 close(fd); fd=-1;
 int failed=0;
 int debug=open("/sys/kernel/qcom-cpufreq-hw/print_cpufreq_debug_regs",O_RDONLY);
 if(debug<0) {perror("debug reader open");return (void*)1;}
 while(!atomic_load(&done)) {
  char clock[128],regs[4096];uint64_t start=ns(CLOCK_MONOTONIC);
  fd=open("/sys/kernel/debug/clk/measure_only_apcs_goldplus_post_acd_clk/clk_measure",O_RDONLY);
  if(fd<0) {perror("clock open");failed=1;break;}
  ssize_t n=read(fd,clock,sizeof(clock)-1);if(n<=0) {perror("clock read");failed=1;break;}clock[n]=0; close(fd);fd=-1;
  uint64_t end=ns(CLOCK_MONOTONIC),hz=strtoull(clock,NULL,10);
  if(lseek(debug,0,SEEK_SET)<0) {perror("debug seek");failed=1;break;}
  n=read(debug,regs,sizeof(regs)-1);if(n<=0) {perror("debug read");failed=1;break;}regs[n]=0;
  char *domain=strstr(regs,"FREQUENCY DOMAIN 2"),*label=domain?strstr(domain,"EPSS_DEBUG_SRB:"):NULL;unsigned srb=~0U;
  if(label)sscanf(label,"EPSS_DEBUG_SRB: %x",&srb);
  printf("H,%llu,%llu,%llu,%u\n",(unsigned long long)start,(unsigned long long)end,(unsigned long long)hz,srb);
  struct timespec pause={0,500000000};nanosleep(&pause,NULL);
 }
 close(debug);if(fd>=0)close(fd);return failed?(void*)1:NULL;
}
int main(int argc,char **argv) {
 if(argc!=3 && argc!=4) {fprintf(stderr,"seconds ready-file\n");return 2;}
 setvbuf(stdout,NULL,_IOLBF,0);
 int use_observer=argc==3 || strcmp(argv[3],"counter-only")!=0;
 unsigned seconds=(unsigned)strtoul(argv[1],NULL,10);if(seconds<1||seconds>15)return 2;
 for(unsigned tries=0;affinity(7);tries++) {
  if(tries>=29){perror("prime affinity");return 2;}
  volatile uint64_t warm=1;uint64_t stop=ns(CLOCK_MONOTONIC_RAW)+100000000;
  while(ns(CLOCK_MONOTONIC_RAW)<stop)warm=warm*1664525+1013904223;
 }
 struct perf_event_attr attr={0};attr.size=sizeof attr;attr.type=PERF_TYPE_HARDWARE;attr.config=PERF_COUNT_HW_CPU_CYCLES;attr.disabled=1;attr.exclude_hv=1;attr.read_format=PERF_FORMAT_TOTAL_TIME_ENABLED|PERF_FORMAT_TOTAL_TIME_RUNNING;
 int fd=syscall(__NR_perf_event_open,&attr,0,-1,-1,0);if(fd<0){perror("perf_event_open");return 2;}
 if(ioctl(fd,PERF_EVENT_IOC_RESET,0)||ioctl(fd,PERF_EVENT_IOC_ENABLE,0)){perror("perf enable");close(fd);return 2;}
 pthread_t observer;if(use_observer && pthread_create(&observer,NULL,observe,NULL)){perror("observer create");close(fd);return 2;}
 FILE *ready=fopen(argv[2],"w");if(!ready){perror("ready file");atomic_store(&done,1);if(use_observer)pthread_join(observer,NULL);close(fd);return 2;}fputs("ready\n",ready);fflush(ready);fsync(fileno(ready));fclose(ready);
 volatile uint64_t x=1;uint64_t finish=ns(CLOCK_MONOTONIC_RAW)+(uint64_t)seconds*1000000000;
 while(ns(CLOCK_MONOTONIC_RAW)<finish) {
  uint64_t mono_a=ns(CLOCK_MONOTONIC),wall_a=ns(CLOCK_MONOTONIC_RAW);
  int cpu_a=sched_getcpu();struct reading a=sample(fd);uint64_t thread_a=ns(CLOCK_THREAD_CPUTIME_ID),until=wall_a+1000000;
  do {for(int i=0;i<128;i++)x=x*1664525+1013904223;}while(ns(CLOCK_MONOTONIC_RAW)<until);
  struct reading b=sample(fd);uint64_t thread_b=ns(CLOCK_THREAD_CPUTIME_ID);int cpu_b=sched_getcpu();uint64_t wall_b=ns(CLOCK_MONOTONIC_RAW),mono_b=ns(CLOCK_MONOTONIC);
  printf("C,%llu,%llu,%d,%d,%llu,%llu,%llu,%llu,%llu,%.9f\n",(unsigned long long)mono_a,(unsigned long long)mono_b,cpu_a,cpu_b,(unsigned long long)(b.count-a.count),(unsigned long long)(b.enabled-a.enabled),(unsigned long long)(b.running-a.running),(unsigned long long)(thread_b-thread_a),(unsigned long long)(wall_b-wall_a),(double)(b.count-a.count)/(wall_b-wall_a));
 }
 atomic_store(&done,1);void *observer_result=NULL;if(use_observer)pthread_join(observer,&observer_result);
 if(ioctl(fd,PERF_EVENT_IOC_DISABLE,0)){perror("perf disable");close(fd);return 2;}close(fd);return observer_result?2:(x==0);
}
