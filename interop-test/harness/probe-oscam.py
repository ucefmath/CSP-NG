#!/usr/bin/env python3
"""Probe OSCam (12346) — validation rapide des comportements CSP (debug local)."""
import socket, struct, time

TYPE_REQUEST, TYPE_REPLY, TYPE_PINGREQ, TYPE_PINGRPL, TYPE_RESENDREQ = 1, 2, 3, 4, 5
OSCAM = ("127.0.0.1", 12346)
PORT = 19001
TAG, ONID, CAID = 0x80, 0x0010, 0x0100

def i16(v): return struct.pack(">H", v & 0xFFFF)
def i32(v): return struct.pack(">i", ((v & 0xFFFFFFFF) + 2**31) % 2**32 - 2**31)
def i64(v): return struct.pack(">q", v)
def ck(tag, sid, onid, caid, h): return bytes([tag]) + i16(sid) + i16(onid) + i16(caid) + i32(h)
def req(sid, h, arb=None):
    b = bytes([TYPE_REQUEST]) + ck(TAG, sid, ONID, CAID, h)
    if arb is not None: b += struct.pack(">d", arb)
    return b
def reply(sid, h, cw=None):
    k = ck(TAG, sid, ONID, CAID, h)
    return bytes([TYPE_REPLY]) + k + bytes([TAG]) + (cw if cw is not None else b"")
def pingreq(): return bytes([TYPE_PINGREQ]) + i64(int(time.time()*1000)) + i32(PORT)
def resend(h): return bytes([TYPE_RESENDREQ]) + i32(PORT) + ck(TAG, 0x0001, ONID, CAID, h)

def ecm_hash(b):
    h = 0
    for x in b: h = (31*h + x) & 0xFFFFFFFF
    return struct.unpack(">i", struct.pack(">I", h))[0]

def mk_cw():
    cw = []
    for g in range(4):
        b0, b1, b2 = 0x10+g*5, 0x31+g*3, 0x52+g*2
        cw += [b0, b1, b2, (b0+b1+b2) & 0xFF]
    return bytes(cw)

s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("0.0.0.0", PORT)); s.settimeout(0.3)

def drain(t=1.0):
    got = []; end = time.time()+t
    while time.time() < end:
        try:
            d, a = s.recvfrom(2048); got.append((d, a))
        except socket.timeout: pass
    return got

def parse_reply(b):
    if len(b) >= 13 and b[0] == TYPE_REPLY:
        return struct.unpack(">i", b[8:12])[0], (b[13:29] if len(b) >= 29 else None)
    return None, None

print("== 1. ping ==")
s.sendto(pingreq(), OSCAM)
got = drain(1.5)
print("PINGRPL recus:", len([d for d, a in got if d and d[0] == TYPE_PINGRPL]))

print("== 2. REQUEST 12 -> constcw ==")
h = ecm_hash(b"probe-constcw")
s.sendto(req(0x0001, h), OSCAM)
got = drain(5.0)
for d, a in got:
    hh, cw = parse_reply(d)
    if hh == h and cw is not None:
        print("REPLY CW:", cw.hex())
    elif d and d[0] == TYPE_REPLY:
        print("REPLY hash", hh, "cw", cw.hex() if cw else None)
print("total recus:", len(got))

print("== 3. RESENDREQ apres requete (cache OSCam) ==")
s.sendto(resend(h), OSCAM)
got = drain(2.0)
for d, a in got:
    hh, cw = parse_reply(d)
    if cw is not None:
        print("RESEND REPLY CW:", cw.hex())

print("== 4. REQUEST 20 (arbitrage) -> ignore sans crash ==")
s.sendto(req(0x0001, ecm_hash(b"probe-arb"), arb=1.5), OSCAM)
got = drain(2.0)
print("reponses recues (attendu aucune):", len([d for d, a in got if d and d[0] == TYPE_REPLY]))

print("== 5. REPLY vide 13 o -> ignore sans crash ==")
s.sendto(reply(0x0001, ecm_hash(b"probe-empty")), OSCAM)
got = drain(1.5)
print("recus:", len(got))

print("== 6. ping apres tout ==")
s.sendto(pingreq(), OSCAM)
got = drain(1.5)
print("PINGRPL apres tests:", len([d for d, a in got if d and d[0] == TYPE_PINGRPL]))
