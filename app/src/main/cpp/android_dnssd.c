/*
 * android_dnssd.c - UxPlay dnssd backend that hands service registration to
 * Android's NsdManager (via callbacks installed by native_bridge.cpp).
 *
 * Implements the "private" backend API declared in UxPlay's lib/dnssd.h, the
 * same contract as lib/dns_sd/dns_sd.c and lib/mdnsd/dnssd_mdnsd.c. TXT records
 * are built here (identically to the reference backend) as raw DNS-TXT bytes,
 * so dnssd_get_*_txt() keep working for /info responses.
 *
 * GPL-3.0 (as UxPlay).
 */
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <assert.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "utils.h"
#include "android_dnssd.h"

#define TXT_MAX 1024
#define MAX_SERVNAME 256   /* same as lib/dns_sd/dns_sd.c */

typedef struct {
    unsigned char bytes[TXT_MAX];
    int len;
} txt_record_t;

typedef struct dnssd_private_s {
    txt_record_t raop_record;
    txt_record_t airplay_record;
    int raop_registered;
    int airplay_registered;
} dnssd_private_t;

static android_dnssd_hooks_t g_hooks;

void android_dnssd_set_hooks(const android_dnssd_hooks_t *hooks) {
    if (hooks) g_hooks = *hooks; else memset(&g_hooks, 0, sizeof(g_hooks));
}

static void txt_reset(txt_record_t *t) { t->len = 0; }

static void txt_set(txt_record_t *t, const char *key, const char *value) {
    size_t klen = strlen(key), vlen = value ? strlen(value) : 0;
    size_t entry = klen + (value ? 1 + vlen : 0);
    if (entry > 255 || t->len + 1 + (int) entry > TXT_MAX) return;
    t->bytes[t->len++] = (unsigned char) entry;
    memcpy(t->bytes + t->len, key, klen); t->len += (int) klen;
    if (value) {
        t->bytes[t->len++] = '=';
        memcpy(t->bytes + t->len, value, vlen); t->len += (int) vlen;
    }
}

void *dnssd_private_init(dnssd_t *dnssd_public, int *error) {
    (void) dnssd_public;
    dnssd_private_t *p = (dnssd_private_t *) calloc(1, sizeof(dnssd_private_t));
    if (!p) { if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }
    if (!g_hooks.register_service) {
        /* Not fatal: the app may advertise via another fallback. */
        if (error) *error = DNSSD_ERROR_NOERROR;
    }
    return p;
}

void dnssd_private_destroy(void *private_data) {
    dnssd_private_t *p = (dnssd_private_t *) private_data;
    if (!p) return;
    if (p->raop_registered && g_hooks.unregister_service) g_hooks.unregister_service(g_hooks.cls, "_raop._tcp");
    if (p->airplay_registered && g_hooks.unregister_service) g_hooks.unregister_service(g_hooks.cls, "_airplay._tcp");
    free(p);
}

void dnssd_error_text(int *error, const char *appname) {
    printf("*** dnssd_implementation: Android NsdManager bridge (%s), error %d\n", appname, error ? *error : 0);
}

int dnssd_register_raop(dnssd_t *dnssd_public, unsigned short port) {
    assert(dnssd_public && dnssd_public->dnssd_private);
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    char features[22] = {0};
    char servname[MAX_SERVNAME];
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);

    txt_record_t *t = &p->raop_record;
    txt_reset(t);
    txt_set(t, "ch", RAOP_CH);
    txt_set(t, "cn", RAOP_CN);
    txt_set(t, "da", RAOP_DA);
    txt_set(t, "et", RAOP_ET);
    txt_set(t, "vv", RAOP_VV);
    txt_set(t, "ft", features);
    txt_set(t, "am", GLOBAL_MODEL);
    txt_set(t, "md", RAOP_MD);
    txt_set(t, "rhd", RAOP_RHD);
    switch (dnssd_public->pin_pw) {
    case 1: txt_set(t, "pw", "true"); txt_set(t, "sf", "0x8c"); break;
    case 2:
    case 3: txt_set(t, "pw", "true"); txt_set(t, "sf", "0x84"); break;
    default: txt_set(t, "pw", "false"); txt_set(t, "sf", RAOP_SF); break;
    }
    txt_set(t, "sr", RAOP_SR);
    txt_set(t, "ss", RAOP_SS);
    txt_set(t, "sv", RAOP_SV);
    txt_set(t, "tp", RAOP_TP);
    txt_set(t, "txtvers", RAOP_TXTVERS);
    txt_set(t, "vs", RAOP_VS);
    txt_set(t, "vn", RAOP_VN);
    txt_set(t, "pk", dnssd_public->pk ? dnssd_public->pk : "");

    if (utils_hwaddr_raop(servname, sizeof(servname), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) return -1;
    if (sizeof(servname) < strlen(servname) + 1 + dnssd_public->name_len + 1) return -2;
    strncat(servname, "@", sizeof(servname) - strlen(servname) - 1);
    strncat(servname, dnssd_public->name, sizeof(servname) - strlen(servname) - 1);

    if (!g_hooks.register_service) return -65537; /* kDNSServiceErr_Unknown: no backend */
    int ret = g_hooks.register_service(g_hooks.cls, "_raop._tcp", servname, port, t->bytes, t->len);
    if (ret == 0) p->raop_registered = 1;
    return ret;
}

int dnssd_register_airplay(dnssd_t *dnssd_public, unsigned short port) {
    assert(dnssd_public && dnssd_public->dnssd_private);
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) return -1;

    txt_record_t *t = &p->airplay_record;
    txt_reset(t);
    txt_set(t, "deviceid", device_id);
    txt_set(t, "features", features);
    txt_set(t, "pw", dnssd_public->pin_pw ? "true" : "false");
    txt_set(t, "flags", "0x4");
    txt_set(t, "model", GLOBAL_MODEL);
    txt_set(t, "pk", dnssd_public->pk ? dnssd_public->pk : "");
    txt_set(t, "pi", AIRPLAY_PI);
    txt_set(t, "srcvers", AIRPLAY_SRCVERS);
    txt_set(t, "vv", AIRPLAY_VV);

    if (!g_hooks.register_service) return -65537;
    int ret = g_hooks.register_service(g_hooks.cls, "_airplay._tcp", dnssd_public->name, port, t->bytes, t->len);
    if (ret == 0) p->airplay_registered = 1;
    return ret;
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd_public, int *length) {
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    *length = p->raop_record.len;
    return (const char *) p->raop_record.bytes;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd_public, int *length) {
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    *length = p->airplay_record.len;
    return (const char *) p->airplay_record.bytes;
}

void dnssd_unregister_raop(dnssd_t *dnssd_public) {
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    if (p && p->raop_registered && g_hooks.unregister_service) g_hooks.unregister_service(g_hooks.cls, "_raop._tcp");
    if (p) p->raop_registered = 0;
}

void dnssd_unregister_airplay(dnssd_t *dnssd_public) {
    dnssd_private_t *p = (dnssd_private_t *) dnssd_public->dnssd_private;
    if (p && p->airplay_registered && g_hooks.unregister_service) g_hooks.unregister_service(g_hooks.cls, "_airplay._tcp");
    if (p) p->airplay_registered = 0;
}

/* NsdManager runs its own responder; there is no socket for UxPlay to poll. */
int dnssd_get_service_fd(dnssd_t *dnssd_public, int service) { (void) dnssd_public; (void) service; return -1; }
int dnssd_process_service(dnssd_t *dnssd_public, int service) { (void) dnssd_public; (void) service; return 0; }
