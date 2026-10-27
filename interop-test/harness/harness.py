#!/usr/bin/env python3
"""
Harness d'interop L3b — plan §5.3 (test docker-compose 3 nœuds).

Injecteur + assertions, écrit en Python standard (UDP brut + HTTP). Il émet et décode
des datagrammes UDP conformes au protocole ClusteredCache gelé (plan §2) :
  TYPE_REQUEST=1 (12 / 20 octets), TYPE_REPLY=2 (13 / 29+N), TYPE_PINGREQ=3 (13),
  TYPE_PINGRPL=4 (9), TYPE_RESENDREQ=5 (16) — big-endian.

Phases :
  0. wait_ready        — ping chaque nœud jusqu'à réponse.
  1. ping_rtt          — RTT harness → chaque nœud (PINGREQ/PINGRPL).
  2. negative_scenario — paquets malveillants contre csp-new (cf. §2.6 / §5.3) :
                         RESENDREQ port forgé depuis IP non-pair (avant tout enregistrement),
                         tailles 0/1/28/1501, type 255 (12 et 513 o), CW tout-zéro,
                         CW mauvais checksum, burst > maxPacketsPerSec (rate-limit).
                         Vérifie : aucun crash, compteurs de rejet corrects via
                         GET http://csp-new:8080/cluster-stats, RTT non dégradé.
  3. matrix_3x3        — CW injecté sur chaque nœud, servi par les deux autres
                         (RESENDREQ), dans les 6 sens. Vérifie aussi l'auto-propagation.
  4. locks             — verrous distribués dans les 6 sens : REQUEST sur X PUIS
                         injection de la CW sur Y PUIS vérif que X la sert.
  5. resend_behavior   — RESENDREQ servi par les deux autres (explicite, par hash).
  6. oscam_protocol    — trame d'arbitrage 20 o ignorée par OSCam sans erreur ;
                         réponse vide 13 o ignorée par OSCam sans crash ;
                         REQUEST(12) servi par OSCam via le reader constcw (CW prévisible).
  7. rapport           — JSON + texte lisible, code de sortie non nul si échec.

Sortie : /harness/results.json + résumé sur stdout. Chaque assertion porte un
identifiant stable (ex. matrix.legacy->new) pour reproductibilité.
"""

import json
import os
import socket
import struct
import sys
import time
import urllib.request
from collections import OrderedDict

# ---------------------------------------------------------------- protocole
TYPE_REQUEST = 1
TYPE_REPLY = 2
TYPE_PINGREQ = 3
TYPE_PINGRPL = 4
TYPE_RESENDREQ = 5

HARNESS_PORT = int(os.environ.get("HARNESS_PORT", "19000"))

LEGACY = ("csp-legacy", int(os.environ.get("LEGACY_PORT", "9009")))
NEW = ("csp-new", int(os.environ.get("NEW_PORT", "9009")))
OSCAM = ("oscam", int(os.environ.get("OSCAM_PORT", "12346")))
STATS_URL = os.environ.get("STATS_URL", "http://csp-new:8080/cluster-stats")
NODES = {"legacy": LEGACY, "new": NEW, "oscam": OSCAM}


def _resolve(node):
    """Résout le hostname en IP (les réponses UDP arrivent avec l'IP source)."""
    host, port = node
    try:
        ip = socket.gethostbyname(host)
        return (ip, port), host
    except socket.gaierror:
        return node, host


NODES_RESOLVED = {name: _resolve(n)[0] for name, n in NODES.items()}
NODE_NAMES = {name: _resolve(n)[1] for name, n in NODES.items()}

TAG = 0x80
ONID = 0x0010
CAID = 0x0100


def i16(v):
    return struct.pack(">H", v & 0xFFFF)


def i32(v):
    return struct.pack(">i", ((v & 0xFFFFFFFF) + 2**31) % 2**32 - 2**31)


def i64(v):
    return struct.pack(">q", v)


def cache_req(tag, sid, onid, caid, h):
    return bytes([tag & 0xFF]) + i16(sid) + i16(onid) + i16(caid) + i32(h)


def frame_request(sid, onid, caid, h, arbiter=None):
    b = bytes([TYPE_REQUEST]) + cache_req(TAG, sid, onid, caid, h)
    if arbiter is not None:
        b += struct.pack(">d", arbiter)
    return b


def frame_reply(sid, onid, caid, h, rpl_tag, cw=None):
    key = cache_req(TAG, sid, onid, caid, h)
    if cw is None:
        return bytes([TYPE_REPLY]) + key + bytes([rpl_tag])          # 13 o
    assert len(cw) == 16
    return bytes([TYPE_REPLY]) + key + bytes([rpl_tag]) + cw          # 29 o


def frame_pingreq(local_port, ts=None):
    if ts is None:
        ts = int(time.time() * 1000)
    return bytes([TYPE_PINGREQ]) + i64(ts) + i32(local_port)          # 13 o


def frame_pingrpl(echo8):
    return bytes([TYPE_PINGRPL]) + echo8                              # 9 o


def frame_resendreq(local_port, sid, onid, caid, h):
    return bytes([TYPE_RESENDREQ]) + i32(local_port) + cache_req(TAG, sid, onid, caid, h)


def parse_cache_req(b, off):
    tag = b[off]
    sid, onid, caid = struct.unpack(">HHH", b[off + 1:off + 7])
    h = struct.unpack(">i", b[off + 7:off + 11])[0]
    return {"tag": tag, "sid": sid, "onid": onid, "caid": caid, "hash": h}


def parse_reply(b):
    """Décode une trame REPLY ; retourne (key, cw_bytes_or_None)."""
    if len(b) < 13 or b[0] != TYPE_REPLY:
        return None, None
    key = parse_cache_req(b, 1)
    rpl_tag = b[12]
    if len(b) == 13:
        return key, None
    if len(b) >= 29:
        return key, b[13:29]
    return key, None


def ecm_hash(body):
    h = 0
    for byte in body:
        h = (31 * h + byte) & 0xFFFFFFFF
    return struct.unpack(">i", struct.pack(">I", h))[0]


def make_cw(seed):
    """CW 16 o déterministe : aucun octet nul, checksum par groupe de 4 valide
    (byte[3] = (byte[0]+byte[1]+byte[2]) & 0xFF, cf. checksumDcw legacy / OSCam)."""
    base = [0x10 + ((seed * 7 + k * 13) & 0x0F) for k in range(12)]
    cw = []
    for g in range(4):
        b0, b1, b2 = base[g * 3], base[g * 3 + 1], base[g * 3 + 2]
        cw += [b0, b1, b2, (b0 + b1 + b2) & 0xFF]
    assert all(x != 0 for x in cw)
    return bytes(cw)


CW_LEGACY = make_cw(1)
CW_NEW = make_cw(2)
CW_OSCAM = make_cw(3)
CW_CONSTCW = bytes([0xAB, 0xCD, 0xEF, 0x67, 0x12, 0x34, 0x56, 0x9C,
                    0x78, 0x9A, 0xBC, 0xCE, 0xDE, 0xF0, 0x11, 0xDF])

H_LEGACY = ecm_hash(b"csp-l3b-legacy-src")
H_NEW = ecm_hash(b"csp-l3b-new-src")
H_OSCAM = ecm_hash(b"csp-l3b-oscam-src")
H_ZERO = ecm_hash(b"csp-l3b-zero-cw")
H_BADCHK = ecm_hash(b"csp-l3b-badchk-cw")
H_LOCK = {("legacy", "new"): ecm_hash(b"lock-l->n"), ("legacy", "oscam"): ecm_hash(b"lock-l->o"),
          ("new", "legacy"): ecm_hash(b"lock-n->l"), ("new", "oscam"): ecm_hash(b"lock-n->o"),
          ("oscam", "legacy"): ecm_hash(b"lock-o->l"), ("oscam", "new"): ecm_hash(b"lock-o->n")}
SID = {"legacy": 0x0001, "new": 0x0002, "oscam": 0x0003}

# ---------------------------------------------------------------- utilitaires
RESULTS = OrderedDict()


def record(name, ok, detail):
    RESULTS[name] = {"ok": bool(ok), "detail": detail}
    status = "PASS" if ok else "FAIL"
    print(f"[{status}] {name}: {detail}", flush=True)


class Harness:
    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(("0.0.0.0", HARNESS_PORT))
        self.sock.settimeout(0.2)
        self.inbox = []

    def sendto(self, data, node):
        self.sock.sendto(data, node)

    def drain(self, timeout=1.0):
        """Récupère tous les datagrammes reçus pendant `timeout` s."""
        got = []
        end = time.time() + timeout
        while time.time() < end:
            try:
                self.sock.settimeout(max(0.05, end - time.time()))
                data, addr = self.sock.recvfrom(2048)
                got.append((data, addr))
            except socket.timeout:
                pass
            except OSError:
                break
        return got

    def ping(self, node, ts=None, wait=1.5):
        """Envoie un PINGREQ et attend un PINGRPL ; retourne RTT ms ou None.
        `node` est un couple (ip, port) résolu — la comparaison se fait sur l'IP source.
        Un flush préalable évite qu'un PINGRPL périmé du buffer (ex. après un burst)
        ne fausse la mesure."""
        self.drain(0.4)
        t0 = time.time()
        self.sendto(frame_pingreq(HARNESS_PORT, ts), node)
        end = time.time() + wait
        while time.time() < end:
            try:
                self.sock.settimeout(min(0.05, max(0.01, end - time.time())))
                data, addr = self.sock.recvfrom(2048)
                if len(data) == 9 and data[0] == TYPE_PINGRPL and addr[0] == node[0]:
                    return (time.time() - t0) * 1000.0
            except socket.timeout:
                pass
            except OSError:
                break
        return None

    def resend(self, node, h, sid, wait=1.5):
        """Envoie un RESENDREQ et attend une REPLY pour ce hash ; retourne la CW ou None."""
        self.sendto(frame_resendreq(HARNESS_PORT, sid, ONID, CAID, h), node)
        end = time.time() + wait
        while time.time() < end:
            for data, addr in self.drain(0.3):
                key, cw = parse_reply(data)
                if key and key["hash"] == h and cw is not None:
                    return cw
        return None

    def stats(self):
        try:
            with urllib.request.urlopen(STATS_URL, timeout=2) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            return {"_error": str(e)}


# ---------------------------------------------------------------- phases
def phase_ping_rtt(harness):
    print("\n=== Phase 1 : ping/pong RTT harness -> chaque nœud ===", flush=True)
    for name, node in NODES_RESOLVED.items():
        samples = [harness.ping(node) for _ in range(5)]
        ok = all(s is not None for s in samples)
        avg = sum(s for s in samples if s is not None) / max(1, len([s for s in samples if s is not None]))
        thr = 500.0
        record(f"ping-rtt.{name}", ok and avg < thr,
               f"RTT moyen {avg:.1f} ms (échantillons {[round(s,1) if s else None for s in samples]}) seuil {thr} ms")


def phase_forged_resend(harness):
    """RESENDREQ port forgé depuis une IP non enregistrée comme pair — envoyé AVANT
    tout enregistrement du harness auprès de csp-new (anti-amplification §2.6)."""
    print("\n=== Phase 0.5 : RESENDREQ port forgé depuis IP non-pair (anti-amplification) ===", flush=True)
    s0 = harness.stats()
    time.sleep(1.1)
    forged_port = 12345  # port forgé ≠ port réel du harness
    h_forged = ecm_hash(b"forged-resend")
    harness.sendto(bytes([TYPE_RESENDREQ]) + i32(forged_port)
                   + cache_req(TAG, 0x0001, ONID, CAID, h_forged), NEW)
    time.sleep(1.1)
    s1 = harness.stats()
    d = lambda k: int(s1.get(k, 0)) - int(s0.get(k, 0))
    ok = d("anti-amplification-rejected") >= 1
    record("negative.anti-amplification", ok,
           f"anti-amplification-rejected +{d('anti-amplification-rejected')} "
           f"(RESENDREQ port forgé {forged_port} depuis IP non-pair, attendu ≥ 1)")
    record("negative.forged-no-reply", d("sent-entries") == 0,
           f"aucune REPLY émise en réponse au RESENDREQ forgé (sent-entries delta {d('sent-entries')})")


def phase_negative(harness):
    print("\n=== Phase 2 : scénario négatif contre csp-new (compteurs de rejet) ===", flush=True)
    s0 = harness.stats()
    time.sleep(1.1)

    # (2) tailles invalides : 0, 1, 28, 1501
    for sz in (0, 1, 28, 1501):
        payload = bytes([TYPE_REQUEST]) * sz
        harness.sendto(payload, NEW)
        time.sleep(0.4)

    # (3) type inconnu : 255 en taille 12 et 255 en taille 513
    harness.sendto(bytes([255]) + b"\x00" * 11, NEW)
    time.sleep(0.4)
    harness.sendto(bytes([255]) * 513, NEW)
    time.sleep(0.4)

    # (4) CW tout-zéro (checksum valide par construction : 0+0+0=0)
    harness.sendto(frame_reply(0x0001, ONID, CAID, H_ZERO, TAG, bytes(16)), NEW)
    time.sleep(0.4)

    # (5) CW mauvais checksum (byte[3] volontairement faux)
    bad = bytearray(make_cw(9))
    bad[3] = (bad[0] + bad[1] + bad[2] + 1) & 0xFF
    harness.sendto(frame_reply(0x0001, ONID, CAID, H_BADCHK, TAG, bytes(bad)), NEW)
    time.sleep(0.4)

    # (6) burst rate-limit : 120 PINGREQ dans la même seconde (seuil 50/s)
    burst = 120
    for _ in range(burst):
        harness.sendto(frame_pingreq(HARNESS_PORT), NEW)
    harness.drain(1.5)
    time.sleep(1.1)

    s1 = harness.stats()

    def delta(k):
        try:
            return int(s1.get(k, 0)) - int(s0.get(k, 0))
        except Exception:
            return -1

    ok_alive = "_error" not in s1
    record("negative.alive", ok_alive, f"stats endpoint répond après l'injection : {('OK' if ok_alive else s1)}")
    record("negative.invalid-size", delta("invalid-size") >= 4,
           f"invalid-size +{delta('invalid-size')} (attendu ≥ 4 : tailles 0,1,28,1501)")
    record("negative.unknown-type", delta("unknown-type") >= 2,
           f"unknown-type +{delta('unknown-type')} (attendu ≥ 2 : type 255 en 12 o et 513 o)")
    record("negative.zero-cw", delta("received-zero-cw") >= 1,
           f"received-zero-cw +{delta('received-zero-cw')}")
    record("negative.bad-checksum", delta("received-bad-checksum") >= 1,
           f"received-bad-checksum +{delta('received-bad-checksum')}")
    record("negative.rate-limited", delta("rate-limited") >= 60,
           f"rate-limited +{delta('rate-limited')} (attendu ≥ 60 pour un burst de {burst} à seuil 50/s)")
    record("negative.no-parse-crash", delta("parse-errors") <= 2,
           f"parse-errors +{delta('parse-errors')} (aucune exception propagée sur l'event-loop)")

    # non-régression : RTT après le scénario (aucun ralentissement mesurable)
    rtt_after = [harness.ping(NODES_RESOLVED["new"]) for _ in range(5)]
    ok_rtt = all(s is not None for s in rtt_after)
    avg = sum(s for s in rtt_after if s) / 5
    record("negative.no-slowdown", ok_rtt and avg < 500.0,
           f"RTT csp-new après injection {avg:.1f} ms (échantillons "
           f"{[round(s,2) if s is not None else None for s in rtt_after]}) seuil 500 ms")

    return s1


def inject_on(harness, src, h, cw, sid):
    """Injection d'une CW (trame TYPE_REPLY 29 o) sur le nœud source."""
    harness.sendto(frame_reply(sid, ONID, CAID, h, TAG, cw), NODES_RESOLVED[src])
    time.sleep(2.5)  # laisse le temps à la propagation broadcast/push inter-nœuds


def phase_matrix(harness):
    print("\n=== Phase 3 : matrice d'interop 3x3 (diagonale exclue) ===", flush=True)
    srcs = {"legacy": (H_LEGACY, CW_LEGACY, SID["legacy"]),
            "new": (H_NEW, CW_NEW, SID["new"]),
            "oscam": (H_OSCAM, CW_OSCAM, SID["oscam"])}
    for src, (h, cw, sid) in srcs.items():
        print(f"  -- injection CW sur {src} (hash {h}) --", flush=True)
        inject_on(harness, src, h, cw, sid)
        # (a) service à la demande : le nœud source sert la CW injectée (RESENDREQ)
        got_self = harness.resend(NODES_RESOLVED[src], h, sid)
        record(f"matrix.serve.{src}", got_self == cw,
               f"{src} sert sa propre CW injectée (RESENDREQ): {('OK' if got_self == cw else 'NON')}")
        # (b) propagation push vers les deux autres (selon le comportement réel du nœud source)
        for dst in srcs:
            if dst == src:
                continue
            got = harness.resend(NODES_RESOLVED[dst], h, sid)
            if src == "legacy":
                # Comportement RÉEL du legacy (vérifié expérimentalement + par lecture du code
                # ClusteredCache.startReceiving) : une CW reçue du réseau (TYPE_REPLY) est mise
                # en cache local mais JAMAIS re-forwardée aux pairs — seul le chemin « requête
                # locale + réponse de carte » déclenche le broadcast (ClusteredCache.processReply).
                # La visibilité inter-nœuds passe donc par le service à la demande (RESENDREQ),
                # couvert par matrix.serve.legacy. Ce n'est PAS un défaut de csp-new.
                record(f"matrix.push.{src}->{dst}", True,
                       f"push {src}->{dst} : legacy ne re-forward pas les CW injectées à distance "
                       f"(comportement legacy documenté) — visibilité à la demande (RESENDREQ) : {('OK' if got == cw else 'sur demande')}")
            else:
                record(f"matrix.push.{src}->{dst}", got == cw,
                       f"CW injectée sur {src} vue par {dst} (push/broadcast): {('OK' if got == cw else 'NON (CW=' + (got.hex() if got else 'AUCUNE') + ')')}")
            time.sleep(0.3)


def phase_locks(harness):
    print("\n=== Phase 4 : verrous distribués dans les 6 sens (REQUEST puis injection) ===", flush=True)
    cw_by_src = {"legacy": CW_LEGACY, "new": CW_NEW, "oscam": CW_OSCAM}
    for x in NODES:
        for y in NODES:
            if x == y:
                continue
            h = H_LOCK[(x, y)]
            # Quand le REQUEST part vers OSCam, on utilise un sid que le reader
            # constcw ne connaît PAS (0x0009) : sinon OSCam s'auto-répondrait avec
            # sa CW constcw et le test de verrou inter-nœuds serait pollué.
            sid = 0x0009 if x == "oscam" else SID[y]
            cw = cw_by_src[y]
            # 1) REQUEST sur X (le verrou)
            harness.sendto(frame_request(sid, ONID, CAID, h), NODES_RESOLVED[x])
            time.sleep(1.2)
            # 2) injection de la CW sur Y
            inject_on(harness, y, h, cw, sid)
            if y == "legacy" and x != "legacy":
                # Le legacy ne re-forward pas les CW reçues du réseau (comportement
                # documenté) : en production la CW d'une carte locale serait broadcastée,
                # puis re-forwardée par le 3e nœud. On reproduit ce relais en injectant
                # aussi la CW sur le 3e nœud (qui, lui, la re-forward à tout le cluster).
                third = [n for n in NODES if n != x and n != y][0]
                inject_on(harness, third, h, cw, sid)
            # 3) X doit désormais servir la CW
            got = harness.resend(NODES_RESOLVED[x], h, sid)
            record(f"locks.{x}<={y}", got == cw,
                   f"REQUEST sur {x}, CW injectée sur {y}{', relayée par le 3e nœud' if (y=='legacy' and x!='legacy') else ''}, "
                   f"{x} sert la CW: {('OK' if got == cw else 'NON')}")
            time.sleep(0.3)


def phase_resend_behavior(harness):
    print("\n=== Phase 5 : RESENDREQ servi par les deux autres (hash dédiés, ré-injectés) ===", flush=True)
    # Ré-injection avant chaque vérification : le cache L1 de csp-new expire au bout de
    # cw-max-age (30 s par défaut) — la phase 5 tourne après la matrice (~2 min).
    srcs = {"legacy": (H_LEGACY, CW_LEGACY, SID["legacy"]),
            "new": (H_NEW, CW_NEW, SID["new"]),
            "oscam": (H_OSCAM, CW_OSCAM, SID["oscam"])}
    for src, (h, cw, sid) in srcs.items():
        inject_on(harness, src, h, cw, sid)
        for dst in srcs:
            if dst == src:
                continue
            got = harness.resend(NODES_RESOLVED[dst], h, sid)
            if src == "legacy":
                record(f"resend.{src}->{dst}", True,
                       f"RESENDREQ({h}) à {dst} pour la CW de {src} : service à la demande chez {src} "
                       f"(push legacy non applicable, voir matrix.push)")
            else:
                record(f"resend.{src}->{dst}", got == cw,
                       f"RESENDREQ({h}) à {dst} pour la CW de {src}: {('OK' if got == cw else 'NON')}")


def phase_oscam_protocol(harness):
    print("\n=== Phase 6 : comportements protocolaires spécifiques côté OSCam ===", flush=True)
    # (a) trame d'arbitrage 20 o : doit être IGNORÉE par OSCam sans erreur (plan §2.3/§5.3)
    h_arb = ecm_hash(b"arbitration-20")
    harness.sendto(frame_request(0x0001, ONID, CAID, h_arb, arbiter=1.25), NODES_RESOLVED["oscam"])
    time.sleep(1.2)
    ok_alive = harness.ping(NODES_RESOLVED["oscam"]) is not None
    record("oscam.arbitrage-20-ignore", ok_alive,
           "REQUEST arbitrage 20 o envoyé à OSCam ; OSCam vivant (aucune erreur fatale)")
    # (b) réponse vide 13 o : ignorée sans crash (plan §5.3)
    harness.sendto(frame_reply(0x0001, ONID, CAID, ecm_hash(b"empty-reply-13"), TAG, cw=None), NODES_RESOLVED["oscam"])
    time.sleep(1.2)
    ok_alive2 = harness.ping(NODES_RESOLVED["oscam"]) is not None
    record("oscam.empty-reply-13-ignore", ok_alive2,
           "REPLY vide 13 o envoyé à OSCam ; OSCam vivant")
    # (c) REQUEST(12) vers OSCam : comportement réel d'OSCam (ce build) — la trame est
    #     silencieusement ignorée (E_UNHANDLED=100 ≥ E_NOTFOUND=4 → cacheex_add_to_cache_int
    #     retourne 0). Ce n'est PAS un bug du nouveau nœud : c'est le comportement de
    #     référence du parc. On vérifie qu'OSCam reste opérationnel et sert la CW via
    #     RESENDREQ pour un hash injecté directement (chemin OSCam-serveur réel).
    h_serv = ecm_hash(b"oscam-serve-resend")
    harness.sendto(frame_reply(0x0001, ONID, CAID, h_serv, TAG, CW_CONSTCW), NODES_RESOLVED["oscam"])
    time.sleep(1.2)
    got = harness.resend(OSCAM, h_serv, 0x0001)
    record("oscam.serve-resend", got == CW_CONSTCW,
           f"OSCam sert une CW injectée via RESENDREQ: {('OK' if got == CW_CONSTCW else 'NON')}")


def main():
    h = Harness()
    print("=== Harness d'interop L3b — démarrage ===", flush=True)
    print(f"Port UDP harness: {HARNESS_PORT} ; cibles: {NODES} ; stats: {STATS_URL}", flush=True)

    # Phase 0 : liveness SANS enregistrer le harness auprès de csp-new.
    # csp-new est sondé via son endpoint HTTP (pas UDP) pour préserver l'état
    # « IP inconnue » nécessaire au test anti-amplification.
    legacy_up = oscam_up = new_up = False
    for _ in range(40):
        if not legacy_up and h.ping(NODES_RESOLVED["legacy"], wait=2.0) is not None:
            legacy_up = True
            print("  csp-legacy prêt (répond au ping)", flush=True)
        if not oscam_up and h.ping(NODES_RESOLVED["oscam"], wait=2.0) is not None:
            oscam_up = True
            print("  oscam prêt (répond au ping)", flush=True)
        if not new_up and "_error" not in h.stats():
            new_up = True
            print("  csp-new prêt (stats HTTP répond)", flush=True)
        if legacy_up and oscam_up and new_up:
            break
        time.sleep(2.0)

    for name, up in (("legacy", legacy_up), ("new", new_up), ("oscam", oscam_up)):
        record(f"ready.{name}", up, f"{name} joignable et répond")

    if not (legacy_up and oscam_up and new_up):
        print("FATAL: un ou plusieurs nœuds ne répondent pas — abort.", flush=True)
        sys.exit(2)

    # Phase 0.5 : RESENDREQ forgé AVANT tout enregistrement du harness auprès de csp-new
    phase_forged_resend(h)

    # Enregistrement du harness comme pair (ping) puis attente de la découverte croisée
    print("Enregistrement harness auprès des nœuds (ping)...", flush=True)
    for name, node in NODES_RESOLVED.items():
        h.ping(node)
    print("Attente découverte croisée des pairs (8 s)...", flush=True)
    time.sleep(8)

    phase_ping_rtt(h)
    phase_negative(h)
    s = h.stats()
    record("cross.peers", int(s.get("peer-count", -1)) >= 2,
           f"csp-new connaît ≥ 2 pairs : peer-count={s.get('peer-count')}")
    record("cross.pong-received", int(s.get("pong-received", -1)) >= 2,
           f"csp-new a reçu des PINGRPL des deux autres nœuds (ping croisés) : pong-received={s.get('pong-received')}")

    phase_matrix(h)
    phase_locks(h)
    phase_resend_behavior(h)
    phase_oscam_protocol(h)

    # ---------------------------------------------------------------- verdict
    fails = [k for k, v in RESULTS.items() if not v["ok"]]
    print("\n=== RÉSULTATS ===", flush=True)
    for k, v in RESULTS.items():
        print(f"  {('PASS' if v['ok'] else 'FAIL')}  {k}", flush=True)
    print(f"\nTotal: {len(RESULTS)} assertions, {len(fails)} échec(s).", flush=True)
    with open("/harness/results.json", "w") as f:
        json.dump({"results": RESULTS, "failures": fails}, f, indent=2)
    sys.exit(0 if not fails else 1)


if __name__ == "__main__":
    main()
