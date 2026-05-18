# Pozyx Hardware Inventory

Captured 2026-05-17 by direct register reads via pypozyx `PozyxSerial` over USB-CDC. Form factor: enclosed **Pozyx Anchor v1.4** units (NOT the bare Creator shields the v7.3 plan originally assumed). Internally identical hardware — the role (anchor vs tag) lives in the `POZYX_OPERATION_MODE` register and can be flipped per unit.

## All 5 units as inventoried

| Sticker / Network ID | WHO_AM_I | Firmware | Factory Role | Notes |
|---|---|---|---|---|
| 0x6E38 | 0x43 ✓ | v1.1 | ANCHOR | 1st inventoried; case was open during visual inspection |
| 0x6000 | 0x43 ✓ | v1.1 | ANCHOR | |
| 0x6165 | 0x43 ✓ | v1.1 | ANCHOR | |
| 0x6167 | 0x43 ✓ | v1.1 | ANCHOR | |
| 0x605F | 0x43 ✓ | v1.1 | ANCHOR | |

## Verification commands

```bash
# Plug ONE unit at a time via micro-USB into dev box.
# udev rule /etc/udev/rules.d/99-pozyx.rules grants 0666 on any STM32 CDC (0483:5740).
cd /home/sai/Desktop/Work/Gemma_Kaggle
.dep/bin/python - <<'PY'
from pypozyx import PozyxSerial, NetworkID, SingleRegister
from pypozyx.definitions.registers import POZYX_WHO_AM_I, POZYX_FIRMWARE_VER, POZYX_OPERATION_MODE
p = PozyxSerial('/dev/ttyACM3')
who=SingleRegister(); p.regRead(POZYX_WHO_AM_I, who)
fw =SingleRegister(); p.regRead(POZYX_FIRMWARE_VER, fw)
nid=NetworkID();      p.getNetworkId(nid)
mode=SingleRegister();p.regRead(POZYX_OPERATION_MODE, mode)
print(f'WHO_AM_I 0x{int(who.value):02X}  fw v{int(fw.value)>>4}.{int(fw.value)&0xF}'
      f'  NetID 0x{nid.id:04X}  role {"ANCHOR" if int(mode.value)==1 else "TAG"}')
PY
```

## Role assignment plan

The system needs 3 anchors (great-room corners, non-coplanar heights per project-plan-v7.3.md §4.3.1) + 1 tag for Margaret's pendant. One unit is spare.

**Recommended roles** (to be locked once Margaret-side hardware is decided):

| Network ID | Proposed role | Reason |
|---|---|---|
| 0x6E38 | ANCHOR (A1) | TBD — pick based on enclosure quality |
| 0x6000 | ANCHOR (A2) | TBD |
| 0x6165 | ANCHOR (A3) | TBD |
| 0x6167 | TAG (T1 — pendant) | Will be flipped via `setOperationMode(POZYX_TAG_MODE)` |
| 0x605F | SPARE | Stays ANCHOR for now; can become T2-cane later |

User picks which physical unit gets which role based on visual condition / mounting convenience.

## How to flip ANCHOR → TAG

```python
from pypozyx import PozyxSerial, NetworkID, SingleRegister, PozyxConstants
from pypozyx.definitions.registers import POZYX_NETWORK_ID, POZYX_OPERATION_MODE

p = PozyxSerial('/dev/ttyACM3')
# Set mode to TAG (0x00 = TAG, 0x01 = ANCHOR per Pozyx register map)
p.setOperationMode(SingleRegister(PozyxConstants.TAG_MODE))
p.saveRegisters([POZYX_NETWORK_ID, POZYX_OPERATION_MODE])
```

`scripts/configure_pozyx.py` wraps this for both directions.
