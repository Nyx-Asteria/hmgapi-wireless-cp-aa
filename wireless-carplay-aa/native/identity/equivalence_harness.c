/* ARM host harness: serialize the same EV contribution used by the former EV Routing owner. */
#include "identity.c"
void *dlopen(const char *p,int f){(void)p;(void)f;return 0;}
void *dlsym(void *h,const char *s){(void)h;(void)s;return 0;}
void *memcpy(void *d,const void *s,u32 n){char *a=d;const char *b=s;while(n--)*a++=*b++;return d;}
void *__aeabi_memcpy(void *d,const void *s,u32 n){char *a=d;const char *b=s;while(n--)*a++=*b++;return d;}
void *__aeabi_memcpy4(void *d,const void *s,u32 n){return __aeabi_memcpy(d,s,n);}

char output[6][1024];
int result;
static u32 at,value=42,mailbox,slot;
static Param root[24],stock_info[5],stock_status[13],stock_wireless[WIRELESS_MAX+1];
static u16 stock_wireless_count=2;
static void put(const char *s){while(*s && at<sizeof(output[0])-1)output[slot][at++]=*s++;output[slot][at]=0;}
static void num(u32 v){
    static const u32 powers[]={1000000000,100000000,10000000,1000000,100000,10000,1000,100,10,1};int started=0;
    for(u32 i=0;i<10;i++){u32 digit=0;while(v>=powers[i]){v-=powers[i];digit++;}if(digit || started || i==9){output[slot][at++]=(char)('0'+digit);started=1;}}output[slot][at]=0;
}
static void *stock_lookup(u16 id,u16 type,u16 *n,void *key,int *more){(void)id;(void)type;(void)n;(void)key;if(more)*more=0;return &value;}
static char wireless_name[]="stock";
static void *wireless_values(u16 id,u16 type,u16 *n,void *key,int *more){
    (void)type;(void)n;(void)key;if(more)*more=0;return id==1?(void*)wireless_name:(void*)&value;
}
static void *root_values(u16 id,u16 type,u16 *n,void *key,int *more){
    (void)type;(void)n;(void)key;if(more)*more=0;
    if(id==20 || id==21)return (void*)stock_lookup;if(id==24)return (void*)wireless_values;return &value;
}
static void *alternate_wireless(u16 id,u16 type,u16 *n,void *key,int *more){
    (void)id;(void)type;(void)n;(void)key;if(more)*more=7;return &value;
}
static void *alternate_root(u16 id,u16 type,u16 *n,void *key,int *more){
    if(id==24){if(more)*more=3;return (void*)alternate_wireless;}
    return root_values(id,type,n,key,more);
}
static const Param *stock_wireless_children(u16 id,u16 type,u16 *count,void *key,int *more){
    (void)id;(void)type;(void)key;if(more)*more=0;*count=stock_wireless_count;return stock_wireless;
}
static void walk(const Param *table,u32 count,Lookup lookup,u16 group){
    for(u32 i=0;i<count;i++){
        const Param *p=&table[i];if(!p->used)continue;
        if(p->type==13){u16 n=0;int more=0;Children children=(Children)p->children;const Param *items=children(p->id,p->type,&n,0,&more);Lookup sub=(Lookup)lookup(p->id,p->type,&n,0,&more);put("[");num(p->id);put(":");walk(items,n,sub,p->id);put("]");continue;}
        if(p->init)((void (*)(u16,u16,u32,void*))p->init)(p->id,p->type,0,0);
        u16 n=0;int more=0;void *v=lookup(p->id,p->type,&n,0,&more);num(group);put(".");num(p->id);put("=");
        if(p->type==10)put((const char*)v);else if(p->type==12)put("");else num(*(u32*)v);put(";");
    }
}
static u32 build(u32 h,u32 s,u32 a,u32 b,u32 cmd,void *table,u32 count,const char *name,Lookup lookup){
    (void)h;(void)s;(void)a;(void)b;(void)cmd;(void)name;at=0;output[slot][0]=0;walk(table,count,lookup,0);return 1;
}
static int mutex(void *p){(void)p;return 0;}
static int protect(void *p,u32 n,int flags){(void)p;(void)n;(void)flags;return 0;}
static u32 fixture_packet[3],fixture_destroyed,fixture_malloc_calls;
static unsigned char fixture_payload[START_MAX];
static u32 fixture_payload_destroyed,fixture_sent;
static void *fixture_create_plain(u32 link,u32 session){
    (void)link;(void)session;
    fixture_packet[1]=fixture_packet[2]=0;
    return fixture_packet;
}
static void *fixture_malloc_withdraw(u32 n,const char *name,u32 flag){
    (void)n;(void)name;(void)flag;
    start_configure(0,0);
    return fixture_payload;
}
static int fixture_send_result=1;
static int fixture_send(void *p){(void)p;fixture_sent++;return fixture_send_result;}
static void *fixture_malloc_plain(u32 n,const char *name,u32 flag){
    (void)n;(void)name;(void)flag;return fixture_payload;
}
static void *fixture_create(u32 link,u32 session){
    (void)link;(void)session;
    // Model a configuration withdrawal during OEM allocation.
    start_configure(0,0);
    return fixture_packet;
}
static void fixture_destroy(void *p){
    if(p==fixture_packet){
        fixture_destroyed++;
        if(fixture_packet[1]==(u32)fixture_payload)fixture_payload_destroyed++;
        fixture_packet[1]=fixture_packet[2]=0;
    }
}
static void *fixture_malloc(u32 n,const char *name,u32 flag){
    (void)n;(void)name;(void)flag;fixture_malloc_calls++;return 0;
}
static void semihost(u32 operation,const void *argument);
int test_main(void){
    root[0]=parameter(20,13);root[0].children=(u32)identity_children;
    root[1]=parameter(21,13);root[1].children=(u32)identity_children;
    root[0].used=root[1].used=0;
    root[2]=parameter(24,13);root[2].children=(u32)stock_wireless_children;
    static const u16 info_ids[5]={0,1,2,6,8};
    for(u32 i=0;i<5;i++)stock_info[i]=parameter(info_ids[i],(info_ids[i]==6 || info_ids[i]==8)?10:8);
    for(u32 i=0;i<13;i++){stock_status[i]=parameter((u16)i,6);stock_status[i].used=0;}
    stock_wireless[0]=parameter(0,5);stock_wireless[1]=parameter(1,10);
    root_table=root;info_table=stock_info;status_table=stock_status;
    root_lookup=root_values;info_lookup=stock_lookup;status_lookup=stock_lookup;wireless_lookup=wireless_values;raw_builder=build;
    lock_mutex=mutex;unlock_mutex=mutex;protect_page=protect;hook_mailbox=&mailbox;
    result=configure("Kia EV6",7,2,0,235000,2,10900,"",0,0);slot=0;
    identity_builder(0,0,0,0,0x1d01,root,24,"identity",root_values);
    if(!identity_built)result|=1;
    result|=configure("Kia EV6",7,2,0,235000,2,10900,"THEME1:985:19.0",15,0);slot=1;
    if(identity_built)result|=1;
    identity_builder(0,0,0,0,0x1d01,root,24,"identity",root_values);
    if(!identity_built)result|=1;
    result|=configure("",0,-1,-1,0,-1,0,"THEME1:985:19.0",15,0);slot=2;
    if(identity_built)result|=1;
    identity_builder(0,0,0,0,0x1d01,root,24,"identity",root_values);
    result|=configure("",0,-1,-1,0,-1,0,"THEME1:985:19.0",15,1);slot=3;
    identity_builder(0,0,0,0,0x1d01,root,24,"identity",root_values);
    result|=configure("Kia K5",6,0,-1,0,-1,0,"",0,0);slot=4;
    identity_builder(0,0,0,0,0x1d01,root,24,"identity",root_values);
    result|=configure("Kia K5",6,0,-1,0,-1,0,"THEME1:985:19.0",15,1);slot=5;
    identity_builder(0,0,0,0,0x1d01,root,24,"identity",root_values);
    if(configure("Kia K5",6,0,0,235000,2,10900,"",0,0)!=-12)result|=1;
    // Exercise OEM tables that already contain theme IDs; preserve other
    // entries, replace only those owned by the selected format.
    stock_wireless[2]=parameter(5,12);stock_wireless[3]=parameter(6,10);
    stock_wireless_count=4;
    u16 child_count=0;int more=0;
    const Param *children=identity_children(24,13,&child_count,0,&more);
    if(child_count!=4 || children[0].id!=0 || children[1].id!=1
       || children[2].id!=5 || children[2].type!=12
       || children[3].id!=6 || children[3].type!=10)result|=1;
    legacy_theme=0;
    stock_wireless[2]=parameter(5,8);
    children=identity_children(24,13,&child_count,0,&more);
    if(child_count!=4 || children[2].id!=5 || children[2].type!=8
       || children[3].id!=6 || children[3].type!=10)result|=1;
    legacy_theme=1;
    // A child list alone is not evidence that the theme value was consumed.
    identity_seen=0;
    children=identity_children(24,13,&child_count,0,&more);
    if(!theme_children_active || (identity_seen&2))result|=1;
    if(wireless_value(6,10,&child_count,0,&more)!=theme || !(identity_seen&2))result|=1;
    // Unsupported stock table size must retain stock values, not inject
    // the configured theme through the lookup wrapper.
    identity_seen=0;stock_wireless_count=WIRELESS_MAX+1;
    children=identity_children(24,13,&child_count,0,&more);
    if(children!=stock_wireless || child_count!=WIRELESS_MAX+1 || theme_children_active
       || wireless_value(6,10,&child_count,0,&more)!=&value || (identity_seen&2))result|=1;
    // Preserve the actual OEM root callback and metadata on fallback.
    build_lookup=alternate_root;
    more=0;
    if(identity_value(24,13,&child_count,0,&more)!=(void*)alternate_wireless || more!=3)result|=1;
    stock_wireless_count=4;
    children=identity_children(24,13,&child_count,0,&more);
    if(!children || identity_value(24,13,&child_count,0,&more)!=(void*)wireless_value)result|=1;
    if(wireless_value(0,5,&child_count,0,&more)!=&value || more!=7)result|=1;
    build_lookup=0;stock_wireless_lookup=0;
    stock_wireless_count=2;
    // Native tuple parser mirrors the Java API, including uint32 bounds.
    if(!theme_tuple_ok("THEME1:985:19.0",15)
       || !theme_tuple_ok("A:4294967295:1",13)
       || !theme_tuple_ok("A:0:1",5)
       || theme_tuple_ok("A:4294967296:1",13)
       || theme_tuple_ok("A:01:1",6)
       || theme_tuple_ok("A::1",4)
       || theme_tuple_ok(":1:1",4)
       || theme_tuple_ok("A:1:",4)
       || theme_tuple_ok("A.B:1:1",7)
       || theme_tuple_ok("A:1:1:2",7))result|=1;
    /* Rejecting malformed replacements must erase the old native payload. */
    static const unsigned char invalid[]={0x40,0x40,0,6,0x43,0};
    start_len=6;for(u32 i=0;i<6;i++)start_msg[i]=0x55;
    if(start_configure(invalid,sizeof(invalid))!=-12 || start_len)result|=1;
    for(u32 i=0;i<START_MAX;i++)if(start_msg[i])result|=1;
    start_len=6;start_msg[0]=0x55;
    if(start_configure(0,6)!=-12 || start_len || start_msg[0])result|=1;
    start_len=6;start_msg[0]=0x55;
    if(start_configure(0,0)!=0 || start_len || start_msg[0])result|=1;
    /* JNI null input takes the invalidation path without touching JNIEnv. */
    start_len=6;start_msg[0]=0x55;
    if(Java_hmgapi_mods_wireless_NativeIdentity_startSession(0,0,0)!=-12 || start_len || start_msg[0])result|=1;
    start_len=6;start_msg[0]=0x55;
    if(start_configure(0,START_MAX+1)!=-12 || start_len || start_msg[0])result|=1;
    // Invalidation during packet creation must suppress the obsolete response.
    packet_create=fixture_create;packet_destroy=fixture_destroy;session_malloc=fixture_malloc;
    start_len=6;start_msg[0]=0x40;
    u32 before=start_sends;
    start_notify(1,2,0,0);
    if(fixture_destroyed!=1 || fixture_malloc_calls || start_sends!=before || start_last!=-4 || start_stale!=1)result|=1;
    start_notify(1,2,0,0);
    if(start_no_payload!=1 || start_sends!=before || start_last!=-1)result|=1;
    // Withdrawal during payload allocation must destroy the populated packet,
    // without copying the obsolete payload or calling the OEM send routine.
    packet_create=fixture_create_plain;session_malloc=fixture_malloc_withdraw;send_packet=fixture_send;
    start_len=6;start_msg[0]=0x40;fixture_payload[0]=0x55;
    start_notify(1,2,0,0);
    if(fixture_destroyed!=2 || fixture_payload_destroyed!=1 || fixture_sent
       || start_sends!=before || start_stale!=2 || start_last!=-4 || fixture_payload[0]!=0x55)result|=1;
    // Zero means dispatch refusal: caller still owns the packet.
    session_malloc=fixture_malloc_plain;fixture_send_result=0;
    start_len=6;start_msg[0]=0x40;
    start_notify(1,2,0,0);
    if(fixture_destroyed!=3 || fixture_payload_destroyed!=2 || fixture_sent!=1
       || start_sends!=before+1 || start_last!=0 || fixture_payload[0]!=0x40)result|=1;
    // Nonzero means the OEM callback took the packet; do not destroy it.
    fixture_send_result=1;
    start_notify(1,2,0,0);
    if(fixture_destroyed!=3 || fixture_payload_destroyed!=2 || fixture_sent!=2
       || start_sends!=before+2 || start_last!=1)result|=1;
    return 0;
}
static const char expected_vehicle[]="[20:20.0=42;20.1=42;20.2=2;20.6=Kia EV6;20.8=Kia EV6;20.11=0;20.11=2;20.12=235000;20.14=10900;][21:21.3=42;21.6=42;21.11=42;21.21=;21.30=;][24:24.0=42;24.1=stock;]";
static const char expected_both[]="[20:20.0=42;20.1=42;20.2=2;20.6=Kia EV6;20.8=Kia EV6;20.11=0;20.11=2;20.12=235000;20.14=10900;][21:21.3=42;21.6=42;21.11=42;21.21=;21.30=;][24:24.0=42;24.1=stock;24.6=THEME1;]";
static const char expected_theme[]="[24:24.0=42;24.1=stock;24.6=THEME1;]";
static const char expected_legacy[]="[24:24.0=42;24.1=stock;24.5=;24.6=THEME1:985:19.0;]";
static const char expected_gas[]="[20:20.0=42;20.1=42;20.2=0;20.6=Kia K5;20.8=Kia K5;][24:24.0=42;24.1=stock;]";
static const char expected_gas_theme[]="[20:20.0=42;20.1=42;20.2=0;20.6=Kia K5;20.8=Kia K5;][24:24.0=42;24.1=stock;24.5=;24.6=THEME1:985:19.0;]";
static const char pass[]="PASS Wireless serializer output is byte-for-byte equivalent to the former EV Routing serializer\n";
static const char fail[]="FAIL Wireless serializer output differs from the former EV Routing serializer\n";
static const char start[]="Running ARM CarPlay identity comparison\n";
static int same(const char *a,const char *b){while(*a && *a==*b){a++;b++;}return *a==*b;}
static void semihost(u32 operation,const void *argument){
    register u32 r0 __asm__("r0")=operation;register const void *r1 __asm__("r1")=argument;
    __asm__ volatile("svc 0x123456"::"r"(r0),"r"(r1):"memory");
}
void run(void){
    semihost(4,start);test_main();int ok=result==0 && same(output[0],expected_vehicle) && same(output[1],expected_both) && same(output[2],expected_theme) && same(output[3],expected_legacy) && same(output[4],expected_gas) && same(output[5],expected_gas_theme);
    if(!ok){semihost(4,output[0]);semihost(4,"\n");semihost(4,output[1]);semihost(4,"\n");semihost(4,output[2]);semihost(4,"\n");}
    semihost(4,ok?pass:fail);
    u32 status[2]={0x20026,ok?0:1};semihost(0x20,status);for(;;){}
}
__attribute__((naked)) void _start(void){__asm__ volatile("ldr sp, =0x01000000\nb run");}
