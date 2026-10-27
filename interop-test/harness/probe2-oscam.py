#!/usr/bin/env python3
"""Probe 2 OSCam — REQUEST(12) déclenche-t-il une réponse si OSCam a la CW en cache ?"""
import socket, struct, time

TYPE_REQUEST, TYPE_REPLY, TYPE_PINGREQ, TYPE_PINGRPL, TYPE_RESENDREQ = 1, 2, 3, 4, 5
OSCAM = ("127.0.0.1", 12346)
PORT = 19002
TAG, ONID, CAID = 0x80, 0x0010, 0x0100

def i16(v): return struct.pack(">H", v & 0xFFFF)
def i32(v): return struct.pack(">i", ((v & 0xFFFFFFFF) + 2**31) % 2**32 - 2**31)
def i64(v): return struct.pack(">q", v)
def ck(tag, sid, onid, caid, h): return bytes([tag]) + i16(sid) + i16(onid) + i16(caid) + i32(h)
def req(sid, h): return bytes([TYPE_REQUEST]) + ck(TAG, sid, ONID, CAID, h)
def reply(sid, h, cw): return bytes([TYPE_REPLY]) + ck(TAG, sid, ONID, CAID, h) + bytes([TAG]) + cw
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

cw = mk_cw()
h = ecm_hash(b"probe-cache-question")

print("== 1. injecter CW sur OSCam (TYPE_REPLY) ==")
s.sendto(reply(0x0001, h, cw), OSCAM)
drain(1.5)

print("== 2. RESENDREQ (devrait répondre si cw en cache) ==")
s.sendto(resend(h), OSCAM)
got = drain(2.0)
for d, a in got:
    hh, cc = parse_reply(d)
    if cc is not None:
        print("  RESEND REPLY CW:", cc.hex(), "match:", cc == cw)

print("== 3. REQUEST(12) sur le même hash (OSCam a la CW) ==")
s.sendto(req(0x0001, h), OSCAM)
got = drain(2.0)
replies = [(parse_reply(d), a) for d, a in got if d and d[0] == TYPE_REPLY]
print("  REPLY reçus:", len(replies))
for (hh, cc), a in replies:
    print("    hash", hh, "cw", cc.hex() if cc else None, "match:", cc == cw)

print("== 4. REQUEST(12) hash inconnu (aucune réponse attendue) ==")
s.sendto(req(0x0001, ecm_hash(b"inconnu")), OSCAM)
got = drain(2.0)
print("  REPLY reçus:", len([d for d, a in got if d and d[0] == TYPE_REPLY]))
