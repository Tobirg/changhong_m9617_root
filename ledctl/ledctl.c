// ledctl.ko 1.0 - Tuvio power LED control, for Tuvio TO48/55/65UFGCV5 (MT5889): the power LED is driven by the
// kernel's pwm_backlight driver ("led-pwm-control-*" device-tree properties, PM PWM via MDrv_PM_PWM_*_Ex).
// No boot image patching: the driver caches the DT values at probe in its private struct and programs them
// in pwm_backlight_suspend()/_resume(); this module rewrites that cache and can drive the PWM directly.
//
// Nothing is hard-coded per kernel build. At load the module disassembles pwm_backlight_suspend() and
// pwm_backlight_resume() of the running kernel and takes the field offsets from the loads that feed
// MDrv_PM_PWM_{Div,Period,DutyCycle,Polarity}_Ex(). If any expected pattern is missing or ambiguous, or the
// values found in RAM do not look sane, it refuses to load and writes nothing. All reads of memory it does
// not own go through probe_kernel_read(), so a wrong guess fails gracefully instead of oopsing.
//
// Parameters (all in /sys/module/ledctl/parameters/, writable at runtime as root):
//   sleep_led   0 = dark in standby, 1 = lit (stock), 2 = blink          [default 0]
//   sleep_level 1..100 brightness when lit in standby (100 = stock full)  [default 100]
//   on_led     -1 = stock (the TV software decides), 0 = dark, 1 = lit, 2 = blink, while the TV is on [default -1].
//              The TV service (mik.ko mi_pm_SetLedControl) reprograms the LED ~1 s after every screen-on, so
//              the Magisk service.sh re-applies this after each "screen_toggled: 1" event (write the same value).
//   on_level    1..100 brightness while on                                [default 100]
//   pwm         debug: write "period duty div polarity" (hex or dec) to program the PWM right now
//   info        read-only: what was found, current values, or why the module refused
//   probe_only  1 at insmod: run every check, write nothing, print "probe ok" and fail with ECANCELED
//               (insmod: "Operation canceled") - how RootMate tells a supported TV without changing it
// rmmod restores everything the driver had when the module was loaded.
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/kallsyms.h>
#include <linux/backlight.h>
#include <linux/notifier.h>
#include <linux/suspend.h>
#include <linux/moduleparam.h>

enum { F_DIV, F_PERIOD, F_DUTY, F_POL, F_N };
static const char *const fsym[F_N] = { "MDrv_PM_PWM_Div_Ex", "MDrv_PM_PWM_Period_Ex",
				       "MDrv_PM_PWM_DutyCycle_Ex", "MDrv_PM_PWM_Polarity_Ex" };

struct fn_layout {
	int base, privoff, support, chan;
	int off[F_N], sz[F_N];
};

struct pwm_vals { u32 v[F_N]; };

typedef long (*probe_read_t)(void *dst, const void *src, size_t size);
typedef int (*size_off_t)(unsigned long addr, unsigned long *size, unsigned long *offset);
typedef struct backlight_device *(*get_by_type_t)(enum backlight_type);
typedef int (*pm_notifier_reg_t)(struct notifier_block *);
typedef void (*pwm_fn_t)(u32 ch, u32 v);

static probe_read_t safe_read;
static pwm_fn_t pwm_set[F_N], pwm_enable, pwm_dben;

static u8 *priv;                 /* driver private struct, valid only after a successful init */
static struct fn_layout sus, res;
static struct pwm_vals orig_sus, orig_res;
static u32 chan;
static char status[400] = "not initialised";

static int sleep_led, sleep_level = 100, on_led = -1, on_level = 100, probe_only;
static bool on_touched;

/* ---------------- AArch64 decoding ---------------- */
static bool dec_bl(u32 w, unsigned long pc, unsigned long *tgt)
{
	if ((w & 0xFC000000) != 0x94000000)
		return false;
	*tgt = pc + (long)((s32)(w << 6) >> 6) * 4;
	return true;
}

/* LDR{B,H,,X} Rt, [Rn, #uimm]; returns access size or 0 */
static int dec_ldr(u32 w, int *rt, int *rn, int *off)
{
	int sz;
	switch (w & 0xFFC00000) {
	case 0x39400000: sz = 1; break;
	case 0x79400000: sz = 2; break;
	case 0xB9400000: sz = 4; break;
	case 0xF9400000: sz = 8; break;
	default: return 0;
	}
	*rt = w & 31;
	*rn = (w >> 5) & 31;
	*off = ((w >> 10) & 0xfff) * sz;
	return sz;
}

static bool dec_cbz(u32 w, int *rt)  /* CBZ/CBNZ, 32 or 64 bit */
{
	if ((w & 0x7E000000) != 0x34000000)
		return false;
	*rt = w & 31;
	return true;
}

#define MAXI 256
static u32 code[MAXI];

static int parse_fn(const char *sym, const unsigned long tgt[F_N], struct fn_layout *L, const char **why)
{
	size_off_t size_off = (size_off_t)kallsyms_lookup_name("kallsyms_lookup_size_offset");
	unsigned long fn = kallsyms_lookup_name(sym), size = 0, offset = 1, t;
	int n, k, b, j, rt, rn, off, sz, first_bl = -1;

	if (!fn || !size_off || !size_off(fn, &size, &offset) || offset != 0) { *why = "function not found"; return -ENOENT; }
	if (size < 0x40 || size > MAXI * 4) { *why = "unexpected function size"; return -EINVAL; }
	n = size / 4;
	if (safe_read(code, (void *)fn, n * 4)) { *why = "code not readable"; return -EFAULT; }

	L->base = L->privoff = L->support = L->chan = -1;
	for (j = 0; j < F_N; j++)
		L->off[j] = L->sz[j] = -1;

	for (k = 0; k < n; k++) {
		if (!dec_bl(code[k], fn + 4 * k, &t))
			continue;
		for (j = 0; j < F_N && tgt[j] != t; j++)
			;
		if (j == F_N)
			continue;
		if (first_bl < 0)
			first_bl = k;
		if (L->off[j] >= 0) { *why = "PWM setter called twice"; return -EINVAL; }
		for (b = 1; b <= 4 && k - b >= 0; b++) {   /* value argument (w1) */
			if (dec_bl(code[k - b], 0, &t)) break;
			sz = dec_ldr(code[k - b], &rt, &rn, &off);
			if (sz && rt == 1) {
				if (L->base < 0) L->base = rn;
				if (rn != L->base) { *why = "loads from different structs"; return -EINVAL; }
				L->off[j] = off; L->sz[j] = sz;
				break;
			}
		}
		for (b = 1; b <= 4 && k - b >= 0; b++) {   /* channel argument (w0) */
			if (dec_bl(code[k - b], 0, &t)) break;
			sz = dec_ldr(code[k - b], &rt, &rn, &off);
			if (sz == 4 && rt == 0 && rn == L->base) {
				if (L->chan >= 0 && L->chan != off) { *why = "channel offset differs"; return -EINVAL; }
				L->chan = off;
				break;
			}
		}
	}
	for (j = 0; j < F_N; j++)
		if (L->off[j] < 0) { *why = "PWM setter load not found"; return -EINVAL; }
	if (L->chan < 0) { *why = "channel load not found"; return -EINVAL; }
	if (L->sz[F_PERIOD] != 2 || L->sz[F_DUTY] != 2 || L->sz[F_POL] != 1 || L->sz[F_DIV] > 2) {
		*why = "unexpected field sizes"; return -EINVAL;
	}
	for (k = 0; k < first_bl; k++) {                  /* the "support" flag check guarding the PWM block */
		sz = dec_ldr(code[k], &rt, &rn, &off);
		if (sz == 8 && rt == L->base && L->privoff < 0)
			L->privoff = off;                     /* base = bl_get_data(bl): ldr xBASE, [xBL, #privoff] */
		if (sz == 1 && rn == L->base && L->support < 0 && k + 1 < n && dec_cbz(code[k + 1], &j) && j == rt)
			L->support = off;
	}
	if (L->privoff < 0 || L->support < 0) { *why = "priv/support load not found"; return -EINVAL; }
	return 0;
}

/* ---------------- field access ---------------- */
static u32 rd(int off, int sz)
{
	return sz == 1 ? *(volatile u8 *)(priv + off) : *(volatile u16 *)(priv + off);
}

static void wr(int off, int sz, u32 v)
{
	if (sz == 1)
		*(volatile u8 *)(priv + off) = v;
	else
		*(volatile u16 *)(priv + off) = v;
}

static void read_set(const struct fn_layout *L, struct pwm_vals *p)
{
	int j;
	for (j = 0; j < F_N; j++)
		p->v[j] = rd(L->off[j], L->sz[j]);
}

static void write_set(const struct fn_layout *L, const struct pwm_vals *p)
{
	int j;
	for (j = 0; j < F_N; j++)
		wr(L->off[j], L->sz[j], p->v[j]);
}

static void pwm_program(const struct pwm_vals *p)  /* same order as the driver */
{
	pwm_enable(chan, 1);
	pwm_dben(chan, 0);
	pwm_set[F_DIV](chan, p->v[F_DIV]);
	pwm_set[F_PERIOD](chan, p->v[F_PERIOD]);
	pwm_set[F_DUTY](chan, p->v[F_DUTY]);
	pwm_set[F_POL](chan, p->v[F_POL]);
	pwm_enable(chan, 1);
}

/* Values for a mode, derived from the driver's own resume (dark) settings. Measured on the Changhong
 * M9617 (2026-11-11): stock div 0xff makes the PWM slow (~1 Hz), so half duty blinks; div 0 makes it fast
 * enough to look steady, and then brightness follows duty/period with the "lit" polarity. */
static void mode_vals(int mode, int level, struct pwm_vals *p)
{
	u32 dark = orig_res.v[F_POL] & 1, per = orig_res.v[F_PERIOD];

	*p = orig_res;
	if (mode <= 0)
		return;
	p->v[F_POL] = !dark;
	p->v[F_DUTY] = per;
	if (mode == 2) {                 /* blink: stock (slow) divider, half duty */
		p->v[F_DUTY] = per / 2;
		return;
	}
	if (level >= 100)
		return;                      /* duty == period: fully lit, stock divider */
	p->v[F_DIV] = 0;
	p->v[F_DUTY] = max(1u, per * (u32)level / 100);
}

static void apply_sleep(void)
{
	struct pwm_vals p;
	if (!priv)
		return;
	mode_vals(sleep_led, sleep_level, &p);
	write_set(&sus, &p);
}

static void apply_on(void)
{
	struct pwm_vals p;
	if (!priv)
		return;
	if (on_led < 0) {            /* back to stock: the driver's own values, normal (dark) state now */
		if (on_touched) {
			write_set(&res, &orig_res);
			pwm_program(&orig_res);
			on_touched = false;
		}
		return;
	}
	mode_vals(on_led, on_level, &p);
	write_set(&res, &p);         /* re-applied by the driver after every resume */
	pwm_program(&p);             /* and right now */
	on_touched = true;
}

static void make_status(void)
{
	struct pwm_vals s, r;
	read_set(&sus, &s);
	read_set(&res, &r);
	scnprintf(status, sizeof(status),
		  "ok ch=%u support@%#x priv@%#x\n"
		  "suspend div@%#x period@%#x duty@%#x pol@%#x = %#x %#x %#x %u (orig %#x %#x %#x %u)\n"
		  "resume  div@%#x period@%#x duty@%#x pol@%#x = %#x %#x %#x %u (orig %#x %#x %#x %u)\n",
		  chan, sus.support, sus.privoff,
		  sus.off[0], sus.off[1], sus.off[2], sus.off[3], s.v[0], s.v[1], s.v[2], s.v[3],
		  orig_sus.v[0], orig_sus.v[1], orig_sus.v[2], orig_sus.v[3],
		  res.off[0], res.off[1], res.off[2], res.off[3], r.v[0], r.v[1], r.v[2], r.v[3],
		  orig_res.v[0], orig_res.v[1], orig_res.v[2], orig_res.v[3]);
}

/* ---------------- parameters ---------------- */
static int parse_int(const char *val, int lo, int hi, int *out)
{
	int v, ret = kstrtoint(val, 0, &v);
	if (ret)
		return ret;
	if (v < lo || v > hi)
		return -ERANGE;
	*out = v;
	return 0;
}

static int get_int(char *buf, const struct kernel_param *kp)
{
	return scnprintf(buf, PAGE_SIZE, "%d\n", *(int *)kp->arg);
}

#define INT_PARAM(name, lo, hi, after)                                                   \
	static int set_##name(const char *val, const struct kernel_param *kp)            \
	{                                                                                \
		int ret = parse_int(val, lo, hi, &name);                                     \
		if (!ret) { after; }                                                         \
		return ret;                                                                  \
	}                                                                                \
	static const struct kernel_param_ops ops_##name = { .set = set_##name, .get = get_int }; \
	module_param_cb(name, &ops_##name, &name, 0644)

INT_PARAM(sleep_led, 0, 2, apply_sleep());
INT_PARAM(sleep_level, 1, 100, apply_sleep());
INT_PARAM(on_led, -1, 2, apply_on());
INT_PARAM(on_level, 1, 100, apply_on());
INT_PARAM(probe_only, 0, 1, (void)0);

static int set_pwm(const char *val, const struct kernel_param *kp)
{
	static const int order[F_N] = { F_PERIOD, F_DUTY, F_DIV, F_POL };
	struct pwm_vals p;
	char tok[16];
	int j, n, ret;

	if (!priv)
		return -ENODEV;
	for (j = 0; j < F_N; j++) {
		while (*val == ' ' || *val == '\t' || *val == '\n')
			val++;
		for (n = 0; *val && *val != ' ' && *val != '\t' && *val != '\n'; val++)
			if (n < (int)sizeof(tok) - 1)
				tok[n++] = *val;
		tok[n] = 0;
		if (!n)
			return -EINVAL;
		ret = kstrtouint(tok, 0, &p.v[order[j]]);
		if (ret)
			return ret;
	}
	if (p.v[F_PERIOD] > 0xffff || p.v[F_DUTY] > 0xffff || p.v[F_DIV] > 0xffff || p.v[F_POL] > 1)
		return -ERANGE;
	pwm_program(&p);
	on_touched = true;
	pr_info("ledctl: pwm period=%#x duty=%#x div=%#x pol=%u\n", p.v[F_PERIOD], p.v[F_DUTY], p.v[F_DIV], p.v[F_POL]);
	return 0;
}
static int get_empty(char *buf, const struct kernel_param *kp) { buf[0] = '\n'; return 1; }
static const struct kernel_param_ops ops_pwm = { .set = set_pwm, .get = get_empty };
module_param_cb(pwm, &ops_pwm, NULL, 0200);

static int get_info(char *buf, const struct kernel_param *kp)
{
	if (priv)
		make_status();
	return scnprintf(buf, PAGE_SIZE, "%s", status);
}
static const struct kernel_param_ops ops_info = { .get = get_info };
module_param_cb(info, &ops_info, NULL, 0444);

/* ---------------- suspend hook ---------------- */
static int ledctl_pm(struct notifier_block *nb, unsigned long ev, void *unused)
{
	if (ev == PM_SUSPEND_PREPARE || ev == PM_HIBERNATION_PREPARE)
		apply_sleep();
	return NOTIFY_DONE;
}
static struct notifier_block ledctl_nb = { .notifier_call = ledctl_pm };

/* ---------------- init / exit ---------------- */
static int fail(int err, const char *why)
{
	scnprintf(status, sizeof(status), "refused: %s\n", why);
	pr_err("ledctl: refused: %s\n", why);
	priv = NULL;
	return err;
}

static int __init ledctl_init(void)
{
	static const enum backlight_type types[] = { BACKLIGHT_RAW, BACKLIGHT_PLATFORM, BACKLIGHT_FIRMWARE };
	get_by_type_t get_by_type = (get_by_type_t)kallsyms_lookup_name("backlight_device_get_by_type");
	pm_notifier_reg_t reg = (pm_notifier_reg_t)kallsyms_lookup_name("register_pm_notifier");
	unsigned long upd = kallsyms_lookup_name("pwm_backlight_update_status"), tgt[F_N], v;
	const char *why = "";
	u8 *p = NULL, snap[0x400];
	int j, ret, maxoff = 0;

	safe_read = (probe_read_t)kallsyms_lookup_name("probe_kernel_read");
	pwm_enable = (pwm_fn_t)kallsyms_lookup_name("MDrv_PM_PWM_Enable_Ex");
	pwm_dben = (pwm_fn_t)kallsyms_lookup_name("MDrv_PM_PWM_Dben_Ex");
	if (!safe_read || !get_by_type || !reg || !upd || !pwm_enable || !pwm_dben)
		return fail(-ENOENT, "kernel symbols not found (not a pwm_backlight/MStar PM PWM kernel)");
	for (j = 0; j < F_N; j++) {
		tgt[j] = kallsyms_lookup_name(fsym[j]);
		pwm_set[j] = (pwm_fn_t)tgt[j];
		if (!tgt[j])
			return fail(-ENOENT, "MDrv_PM_PWM_*_Ex not found");
	}
	if (parse_fn("pwm_backlight_suspend", tgt, &sus, &why))
		return fail(-EINVAL, why);
	if (parse_fn("pwm_backlight_resume", tgt, &res, &why))
		return fail(-EINVAL, why);
	if (sus.privoff != res.privoff || sus.chan != res.chan || sus.support != res.support)
		return fail(-EINVAL, "suspend/resume disagree on struct layout");
	for (j = 0; j < F_N; j++) {
		if (sus.off[j] == res.off[j])
			return fail(-EINVAL, "suspend and resume use the same field");
		maxoff = max(maxoff, max(sus.off[j], res.off[j]) + 2);
	}
	maxoff = max(maxoff, max(sus.chan, sus.support) + 4);
	if (maxoff > sizeof(snap))
		return fail(-EINVAL, "fields too far into the struct");

	for (j = 0; j < ARRAY_SIZE(types) && !p; j++) {
		struct backlight_device *bl = get_by_type(types[j]);
		const struct backlight_ops *ops = NULL;
		if (!bl)
			continue;
		if (safe_read(&ops, &bl->ops, sizeof(ops)) || !ops ||
		    safe_read(&v, &ops->update_status, sizeof(v)) || v != upd)
			continue;                      /* some other backlight driver */
		if (safe_read(&p, (u8 *)bl + sus.privoff, sizeof(p)) || !p)
			p = NULL;
	}
	if (!p)
		return fail(-ENODEV, "pwm_backlight device not found");
	if (safe_read(snap, p, maxoff))
		return fail(-EFAULT, "driver data not readable");

	priv = snap;                               /* validate on the snapshot first */
	chan = *(u32 *)(snap + sus.chan);
	read_set(&sus, &orig_sus);
	read_set(&res, &orig_res);
	priv = NULL;
	if (snap[sus.support] != 1)
		return fail(-ENODEV, "LED PWM control not enabled in device tree");
	if (chan > 15 || orig_sus.v[F_POL] > 1 || orig_res.v[F_POL] > 1 || !orig_res.v[F_PERIOD] ||
	    !orig_sus.v[F_PERIOD] || orig_res.v[F_DUTY] > orig_res.v[F_PERIOD])
		return fail(-EINVAL, "driver data does not look sane");

	if (probe_only) {                          /* dry run for installers: everything checked, nothing written */
		pr_info("ledctl: probe ok (ch=%u), not staying loaded\n", chan);
		return -ECANCELED;
	}

	priv = p;
	ret = reg(&ledctl_nb);
	if (ret)
		return fail(ret, "register_pm_notifier failed");
	apply_sleep();
	apply_on();
	make_status();
	pr_info("ledctl: %s", status);
	return 0;
}

static void __exit ledctl_exit(void)
{
	pm_notifier_reg_t unreg = (pm_notifier_reg_t)kallsyms_lookup_name("unregister_pm_notifier");
	if (unreg)
		unreg(&ledctl_nb);
	if (priv) {
		write_set(&sus, &orig_sus);
		write_set(&res, &orig_res);
		if (on_touched)
			pwm_program(&orig_res);
		pr_info("ledctl: unloaded, driver values restored\n");
	}
}

module_init(ledctl_init);
module_exit(ledctl_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Tuvio power LED control (Tuvio TO48/55/65UFGCV5)");
MODULE_VERSION("1.0");

/* ---- what kbuild's modpost would normally generate (ledctl.mod.c) ---- */
#include <linux/vermagic.h>
#include <linux/compiler.h>

MODULE_INFO(vermagic, "4.19.116++ SMP preempt mod_unload modversions aarch64");
MODULE_INFO(name, KBUILD_MODNAME);

__visible struct module __this_module
__attribute__((section(".gnu.linkonce.this_module"))) = {
	.name = KBUILD_MODNAME,
	.init = init_module,
	.exit = cleanup_module,
	.arch = MODULE_ARCH_INIT,
};

/* CRCs from the __versions sections of the TV's own /vendor/lib/modules (build-specific: a kernel
 * with other CRCs rejects the module before any of its code runs) */
static const struct modversion_info ____versions[]
__used __attribute__((section("__versions"))) = {
	{ 0xe4b2de2d, "module_layout" },
	{ 0x7c32d0f0, "printk" },
	{ 0xe007de41, "kallsyms_lookup_name" },
	{ 0x4ca9669f, "scnprintf" },
	{ 0x2276db98, "kstrtoint" },
	{ 0x69ad2f20, "kstrtouint" },
};

MODULE_INFO(depends, "");
