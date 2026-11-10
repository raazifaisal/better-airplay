/* Hooks that let UxPlay's dnssd layer delegate to Android NsdManager. GPL-3.0. */
#ifndef ANDROID_DNSSD_H
#define ANDROID_DNSSD_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    void *cls;
    /* type: "_raop._tcp" / "_airplay._tcp"; txt: raw DNS TXT bytes (length-prefixed entries). Return 0 on success. */
    int (*register_service)(void *cls, const char *type, const char *name, unsigned short port,
                            const unsigned char *txt, int txt_len);
    void (*unregister_service)(void *cls, const char *type);
} android_dnssd_hooks_t;

void android_dnssd_set_hooks(const android_dnssd_hooks_t *hooks);

#ifdef __cplusplus
}
#endif
#endif
