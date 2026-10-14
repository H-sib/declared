"""An independent evaluation of the attack-path rules (THREAT_MODEL.md section 5, STAGE2_DESIGN.md section 4). Standard library only.

This is written from the design documents and from what `adb` reports, NOT from the Kotlin engine: the rules are nested
tuples of AND/OR over named facts, the logic is Kleene's three values, and the smallest fix set is found by trying every
combination of levers. `conformance_m7.py` compares the app's own answer with this one on a real emulator.

Values: 0 = false, 1 = unknown, 2 = true. AND is the minimum and OR the maximum.
"""
import itertools

F, U, T = 0, 1, 2
CONF = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}
CONF_NAME = {v: k for k, v in CONF.items()}
SEV = ["LOW", "MEDIUM", "HIGH", "CRITICAL"]

PATCH_LIMIT = {"GENERAL": 365, "HIGH_RISK": 180}

# severity per goal for (GENERAL, HIGH_RISK): THREAT_MODEL.md section 6
SEVERITY = {
    "p1": ("HIGH", "CRITICAL"), "p2": ("MEDIUM", "HIGH"), "p5": ("HIGH", "CRITICAL"),
    "p3-ca": ("MEDIUM", "HIGH"), "p3-proxy": ("MEDIUM", "HIGH"), "p3-wifi": ("MEDIUM", "HIGH"),
    "p4-no-lock": ("HIGH", "CRITICAL"), "p6-patch": ("MEDIUM", "HIGH"), "p6-advanced-protection": ("LOW", "HIGH"),
}

# lever id -> (cost, the facts it makes false). Per-app levers are generated below.
PHONE_LEVERS = {
    "private-dns-on": (1, ["dns_off"]),
    "avoid-open-wifi": (2, ["open_wifi"]),
    "remove-ca": (2, ["user_ca"]),
    "clear-proxy": (1, ["proxy"]),
    "set-lock": (1, ["no_lock"]),
    "update-phone": (2, ["patch_old"]),
    "advanced-protection-on": (2, ["ap_off"]),
    "authenticator": (3, ["told_sms"]),
    "avoid-public-wifi": (2, ["told_wifi"]),
}


def tri(flag):
    return U if flag is None else (T if flag else F)


def told_value(answer):
    return {"YES": T, "NO": F, "UNSURE": U}[answer]


def phone_facts(truth, told):
    """The facts about the phone itself, each (value, confidence) and, when unknown, the reason it is."""
    v = {}
    v["no_lock"] = (tri(None if truth["lock_set"] is None else not truth["lock_set"]), "HIGH")
    ap = truth["advanced_protection"]
    v["ap_off"] = {"unsupported": (F, "HIGH"), "off": (T, "HIGH"), "on": (F, "HIGH"), "unknown": (U, "LOW")}[ap]
    present = truth["network_present"]
    v["proxy"] = (U, "LOW") if present is None else ((F, "HIGH") if not present else (tri(truth["proxy"]), "HIGH"))
    if present is None:
        v["dns_off"] = (U, "LOW")
    elif not present:
        v["dns_off"] = (F, "HIGH")
    elif truth["private_dns_active"] is None:
        v["dns_off"] = (U, "LOW")
    else:
        v["dns_off"] = (tri(not truth["private_dns_active"]), "HIGH")
    if truth["wifi_connected"] is None:
        v["open_wifi"] = (U, "LOW")
    elif not truth["wifi_connected"]:
        v["open_wifi"] = (F, "HIGH")
    elif truth["wifi_open"] is None:
        v["open_wifi"] = (U, "LOW")
    else:
        v["open_wifi"] = (tri(truth["wifi_open"]), "HIGH")
    v["user_ca"] = (U, "LOW") if truth["user_cas"] is None else (tri(truth["user_cas"]), "MEDIUM")
    age = truth["patch_age_days"]
    limit = PATCH_LIMIT[told["profile"]]
    v["patch_old"] = (U, "LOW") if age is None else (tri(age >= limit), "HIGH")
    v["told_sms"] = (told_value(told["sms_codes"]), "MEDIUM" if told["sms_codes"] != "UNSURE" else "LOW")
    v["told_wifi"] = (told_value(told["public_wifi"]), "MEDIUM" if told["public_wifi"] != "UNSURE" else "LOW")
    return v


def app_facts(pkg, a, answer, signer_changed):
    """Facts about one package that is not built in. Returns {name: (value, confidence)}."""
    v = {}
    if answer == "NOT_RECOGNIZED":
        v["unf"] = (T, "HIGH")
    elif signer_changed:
        v["unf"] = (T, "HIGH")
    elif answer == "RECOGNIZED":
        v["unf"] = (F, "HIGH")
    elif a["tier_unusual"]:
        v["unf"] = (T, a["tier_confidence"])
    else:
        v["unf"] = (F, a["tier_confidence"])
    if a["a11y_on"] and a["a11y_window_content"]:
        v["a11y"] = (T, "HIGH")
    if a["notif_on"]:
        v["notif"] = (T, "HIGH")
    if a["sms"]:
        v["sms"] = (T, "HIGH")
    if a["admin_on"]:
        v["admin"] = (T, "HIGH")
    if a["overlay"]:
        v["overlay"] = (T, "HIGH")
    if (v.get("admin") or v.get("a11y")) and a["hidden_icon"]:
        if a["location"]:
            v["loc"] = (T, "HIGH")
        if a["mic"]:
            v["mic"] = (T, "HIGH")
        if "loc" in v or "mic" in v:
            v["hidden"] = (T, "HIGH")
    return v


def goals_for(apps_facts):
    """Goal id -> formula over fact names. Per app: p1, p2, p5 (only where the facts they need can exist)."""
    goals = {
        "p3-ca": ("var", "user_ca"),
        "p3-proxy": ("var", "proxy"),
        "p3-wifi": ("or", ("and", ("var", "open_wifi"), ("var", "dns_off")), ("and", ("var", "told_wifi"), ("var", "dns_off"))),
        "p4-no-lock": ("var", "no_lock"),
        "p6-patch": ("var", "patch_old"),
        "p6-advanced-protection": ("var", "ap_off"),
    }
    for pkg, v in apps_facts.items():
        key = lambda n, pkg=pkg: f"{n}@{pkg}"  # noqa: E731
        screen = ("var", key("a11y")) if "a11y" in v else None
        if screen:
            goals[f"p1:{pkg}"] = ("and", screen, ("var", key("unf")))
        ways = [("var", key(n)) for n in ("sms", "notif") if n in v] + ([screen] if screen else [])
        if ways:
            goals[f"p2:{pkg}"] = ("and", ("or", *ways) if len(ways) > 1 else ways[0], ("var", key("unf")), ("var", "told_sms"))
        control = [("var", key(n)) for n in ("admin",) if n in v] + ([screen] if screen else [])
        if control and "hidden" in v:
            follow = [("var", key(n)) for n in ("loc", "mic") if n in v]
            goals[f"p5:{pkg}"] = ("and", ("or", *control) if len(control) > 1 else control[0], ("var", key("hidden")),
                                  ("or", *follow) if len(follow) > 1 else follow[0], ("var", key("unf")))
    return goals


def levers_for(apps_facts, a11y_keys, notif_keys, admin_keys):
    """Lever id -> (cost, facts it flips)."""
    lv = dict(PHONE_LEVERS)
    for pkg, v in apps_facts.items():
        power = [f"{n}@{pkg}" for n in ("a11y", "notif", "sms", "admin", "overlay", "loc", "mic", "hidden") if n in v]
        if "a11y" in v:
            lv[f"a11y-off:{a11y_keys[pkg]}"] = (2, [f"a11y@{pkg}"])
        if "notif" in v:
            lv[f"notif-off:{notif_keys[pkg]}"] = (2, [f"notif@{pkg}"])
        if "sms" in v:
            lv[f"sms-off:{pkg}"] = (2, [f"sms@{pkg}"])
        if "admin" in v:
            lv[f"admin-off:{admin_keys[pkg]}"] = (2, [f"admin@{pkg}"])
        if "overlay" in v:
            lv[f"overlay-off:{pkg}"] = (1, [f"overlay@{pkg}"])
        if power:
            lv[f"remove:{pkg}"] = (3, power)
    return lv


def evaluate(formula, values):
    """(value, confidence or None). Confidence: weakest link of an AND, best way of an OR, only for true nodes."""
    kind = formula[0]
    if kind == "var":
        val, conf = values[formula[1]]
        return val, (CONF[conf] if val == T else None)
    parts = [evaluate(f, values) for f in formula[1:]]
    if kind == "and":
        val = min(p[0] for p in parts)
        return val, (min(p[1] for p in parts) if val == T else None)
    val = max(p[0] for p in parts)
    return val, (max(p[1] for p in parts if p[0] == T) if val == T else None)


def variables(formula):
    if formula[0] == "var":
        return {formula[1]}
    out = set()
    for f in formula[1:]:
        out |= variables(f)
    return out


def with_flipped(values, flips):
    out = dict(values)
    for name in flips:
        if name in out:
            out[name] = (F, out[name][1])
    return out


def analyse(truth, told, apps, answers=None, signer_changed=(), keys=None):
    """The whole answer: paths, not-ruled-out goals, and the smallest fix set. `apps` maps package to its truth dict."""
    answers = answers or {}
    keys = keys or {"a11y": {}, "notif": {}, "admin": {}}
    values = phone_facts(truth, told)
    apps_facts = {pkg: app_facts(pkg, a, answers.get(pkg), pkg in signer_changed) for pkg, a in apps.items() if not a.get("built_in")}
    for pkg, facts in apps_facts.items():
        for name, val in facts.items():
            values[f"{name}@{pkg}"] = val
    goals = goals_for(apps_facts)
    levers = levers_for(apps_facts, keys["a11y"], keys["notif"], keys["admin"])
    result = {}
    for gid, formula in goals.items():
        val, conf = evaluate(formula, values)
        result[gid] = (val, conf)
    paths = {g: r for g, r in result.items() if r[0] == T}
    open_goals = {g for g, r in result.items() if r[0] == U}
    profile_index = 0 if told["profile"] == "GENERAL" else 1
    severity = {g: SEVERITY[g.split(":")[0]][profile_index] for g in paths}
    plan = smallest_fix(goals, values, levers, paths)
    return {"paths": paths, "open": open_goals, "severity": severity, "plan": plan, "values": values, "goals": goals, "levers": levers}


def smallest_fix(goals, values, levers, paths):
    """Fewest levers, then cheapest, then first by lever id order, such that no stoppable path stays true. Brute force."""
    all_flips = [n for _, flips in levers.values() for n in flips]
    everything = with_flipped(values, all_flips)
    stoppable = [g for g in paths if evaluate(goals[g], everything)[0] != T]
    unstoppable = sorted(g for g in paths if g not in stoppable)
    if not stoppable:
        return {"levers": [], "cut": [], "unstoppable": unstoppable}
    relevant_vars = set().union(*(variables(goals[g]) for g in stoppable))
    ids = sorted(i for i, (_, flips) in levers.items() if any(f in relevant_vars for f in flips))
    for k in range(1, len(ids) + 1):
        best = None
        for combo in itertools.combinations(range(len(ids)), k):
            flips = [n for i in combo for n in levers[ids[i]][1]]
            after = with_flipped(values, flips)
            if all(evaluate(goals[g], after)[0] != T for g in stoppable):
                cost = sum(levers[ids[i]][0] for i in combo)
                key = (cost, combo)
                if best is None or key < best:
                    best = key
        if best is not None:
            chosen = [ids[i] for i in best[1]]
            after = with_flipped(values, [n for i in best[1] for n in levers[ids[i]][1]])
            cut = sorted(g for g in paths if evaluate(goals[g], after)[0] != T)
            return {"levers": chosen, "cut": cut, "unstoppable": unstoppable}
    return {"levers": [], "cut": [], "unstoppable": unstoppable}
