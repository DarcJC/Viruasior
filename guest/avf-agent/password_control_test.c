#include "password_control.h"
#include <assert.h>
#include <string.h>
#include <stdio.h>
int main(void) {
    unsigned char password[DROID_PASSWORD_MAX+1]; size_t length=0;
    assert(decode_droid_password("SET_DROID_PASSWORD cXVvdGU6ICckYA==",password,&length));
    assert(length==10 && memcmp(password,"quote: '$`",10)==0);
    const char *invalid[]={"","SET_DROID_PASSWORD ","SET_DROID_PASSWORD YQo=",
        "SET_DROID_PASSWORD YQA=","SET_DROID_PASSWORD YQ0=","SET_DROID_PASSWORD YR==",
        "SET_DROID_PASSWORD YQ==junk","SET_DROID_PASSWORD ????","SET_DROID_PASSWORD YQ=",
        "SET_DROID_PASSWORD YQ==\n","SET_ROOT_PASSWORD YQ=="};
    for(size_t i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++) assert(!decode_droid_password(invalid[i],password,&length));
    char large[256]="SET_DROID_PASSWORD ";memset(large+19,'Y',172);large[191]=0;
    assert(!decode_droid_password(large,password,&length));
    puts("PASS: fixed-user password framing and injection rejection");
}
