/* SPDX-License-Identifier: GPL-3.0-or-later
 * Adapted from the CarPlay Ultra-Lite 1.0.0 bundle's
 * egmp-carplay-ev-routing 0.4.3-ioniq6.15, src/native/identity.c.
 * Included in the Wireless serializer: one owner for the verified iAP2 gate.
 * The original dispatch ABI is retained; payloads are never logged.
 */
#define START_MAX 512
#define AVAILABILITY 0x4300
typedef struct {u32 name,id,label,arg,notify,reply;} StartHandler;
typedef int (*Blob)(unsigned char *,int,u32,u32,u32);
static unsigned char start_msg[START_MAX];
static u32 start_len,start_offers,start_sends;
static int start_last;
static Blob stock_sent_blob,stock_received_blob;
static void *(*packet_create)(u32,u32);
static void (*packet_destroy)(void *);
static void *(*session_malloc)(u32,const char *,u32);
static int (*send_packet)(void *);
static StartHandler *handlers;
_Static_assert(sizeof(StartHandler)==24,"control dispatch ABI");

static void start_clear_locked(void){
    start_len=0;
    for(u32 i=0;i<START_MAX;i++)start_msg[i]=0;
}
static u32 start_notify(u32 link,u32 session,const unsigned char *params,u32 n){
    (void)params;(void)n;
    __atomic_add_fetch(&start_offers,1,__ATOMIC_RELEASE);
    unsigned char snapshot[START_MAX];u32 len=0;
    if(lock_mutex(&identity_mutex))return 0;
    if(__atomic_load_n(&configured,__ATOMIC_ACQUIRE) && has_theme){
        len=start_len;
        for(u32 i=0;i<len;i++)snapshot[i]=start_msg[i];
    }
    unlock_mutex(&identity_mutex);
    if(!len){__atomic_store_n(&start_last,-1,__ATOMIC_RELEASE);return 0;}
    void *packet=packet_create(link,session);
    if(!packet){__atomic_store_n(&start_last,-2,__ATOMIC_RELEASE);return 0;}
    unsigned char *buf=session_malloc(len,"carplaystart",1);
    if(!buf){packet_destroy(packet);__atomic_store_n(&start_last,-3,__ATOMIC_RELEASE);return 0;}
    for(u32 i=0;i<len;i++)buf[i]=snapshot[i];
    ((u32*)packet)[1]=(u32)buf;((u32*)packet)[2]=len;
    int rc=send_packet(packet);
    __atomic_store_n(&start_last,rc,__ATOMIC_RELEASE);
    __atomic_add_fetch(&start_sends,1,__ATOMIC_RELEASE);
    return 0;
}
static int start_append(Blob stock,unsigned char *buf,int cap,u32 id,u32 x,u32 y,unsigned char last){
    if(!stock)return -1;
    int n=stock(buf,cap,id,x,y);
    if(n<0)return n;
    if(!buf)return n+2;
    if(cap<n || cap-n<2)return n;
    buf[n]=0x43;buf[n+1]=last;return n+2;
}
static int start_sent_blob(unsigned char *buf,int cap,u32 id,u32 x,u32 y){
    return start_append(stock_sent_blob,buf,cap,id,x,y,1);
}
static int start_received_blob(unsigned char *buf,int cap,u32 id,u32 x,u32 y){
    return start_append(stock_received_blob,buf,cap,id,x,y,0);
}
static void start_bind(void *lib){
    packet_create=dlsym(lib,"iAP2SessionPacketCreate");
    packet_destroy=dlsym(lib,"iAP2SessionPacketDestroy");
    session_malloc=dlsym(lib,"iAP2SessionMalloc");
    send_packet=dlsym(lib,"iAP2ControlSessionSendPacket");
    handlers=dlsym(lib,"iAP2CtlHandleMsgFuncList");
}
static int start_configure(const unsigned char *msg,u32 len){
    if(len>START_MAX || (len && (len<6 || msg[0]!=0x40 || msg[1]!=0x40
       || ((u32)msg[2]<<8|msg[3])!=len || msg[4]!=0x43 || msg[5]!=0x01)))return -12;
    if(!lock_mutex || !unlock_mutex)return len?-18:0;
    if(lock_mutex(&identity_mutex))return -15;
    start_clear_locked();
    if(!len){unlock_mutex(&identity_mutex);return 0;}
    if(!__atomic_load_n(&configured,__ATOMIC_ACQUIRE) || !has_theme || !handlers
       || !packet_create || !packet_destroy || !session_malloc || !send_packet || !root_table){
        unlock_mutex(&identity_mutex);return -18;
    }
    int sent=0,received=0;
    for(u32 i=0;i<24;i++){
        if(root_table[i].id==6 && root_table[i].length)sent=1;
        if(root_table[i].id==7 && root_table[i].length)received=1;
    }
    if(!sent || !received){unlock_mutex(&identity_mutex);return -20;}
    StartHandler *h=&handlers[33];
    /* Same verified stock duplicate 0x5202 slot as the bundled original.
       Never overwrite an unknown handler or a foreign extension. */
    if(handlers[32].id!=0x5202 || (h->id!=0x5202 && (h->id!=AVAILABILITY || h->notify!=(u32)start_notify))){
        unlock_mutex(&identity_mutex);return -20;
    }
    if(h->id!=AVAILABILITY){
        void *page=(void*)((u32)h&~4095U);
        u32 span=((u32)(h+1)-(u32)page+4095U)&~4095U;
        if(protect_page(page,span,3)){unlock_mutex(&identity_mutex);return -19;}
        h->notify=(u32)start_notify;h->reply=0;h->arg=0;
        __atomic_store_n(&h->id,AVAILABILITY,__ATOMIC_RELEASE);
    }
    for(u32 i=0;i<len;i++)start_msg[i]=msg[i];
    start_len=len;
    unlock_mutex(&identity_mutex);
    return 0;
}
