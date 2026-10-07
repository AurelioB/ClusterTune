#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/syscall.h>
static void show(const char *path) {
 FILE *f=fopen(path,"r"); char b[512]; if (!f) {perror(path);return;}
 printf("PATH %s\n",path);
 while(fgets(b,sizeof b,f)) if (strstr(path,"status")==NULL || !strncmp(b,"Uid:",4) || !strncmp(b,"Cap",3) || !strncmp(b,"Seccomp",7) || !strncmp(b,"NoNewPrivs",10)) fputs(b,stdout);
 fclose(f);putchar('\n');
}
int main(void) {
 unsigned char invalid_image[64]={0};long rc;
 show("/proc/self/status");show("/proc/self/attr/current");show("/sys/fs/selinux/enforce");
 show("/proc/sys/kernel/tainted");show("/proc/sys/kernel/modules_disabled");
 errno=0;rc=syscall(__NR_init_module,invalid_image,sizeof invalid_image,"");
 printf("init_module invalid-zero-header rc=%ld errno=%d (%s)\n",rc,errno,strerror(errno));
 errno=0;rc=syscall(__NR_finit_module,-1,"",0);
 printf("finit_module invalid-fd rc=%ld errno=%d (%s)\n",rc,errno,strerror(errno));
 show("/proc/sys/kernel/tainted");return 0;
}
