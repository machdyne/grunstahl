# Grünstahl

Grünstahl is a USB-C device that provides access to 2 KB of Ferroelectric RAM over USB HID.

![Grünstahl](https://github.com/machdyne/grunstahl/blob/ca5650d2654c76f4da57ea1b40a7fa515e2e3678/grunstahl.png)

This contains schematics, firmware, documentation and a 3D printable case.

See the [product page](https://machdyne.com/product/grunstahl) for more information.

## Software

The 'hidxfer' utility or the WebHID-based hex/text editor can be used to access the data. There is also a native Android version of the hex/text editor.

## Building hidxfer (Linux)

```bash
cd sw
sudo apt install libhidapi-dev
make
```

On Linux you may need a udev rule to access the device without root.
Create /etc/udev/rules.d/99-grunstahl.rules:

  SUBSYSTEM=="hidraw", ATTRS{idVendor}=="1209", ATTRS{idProduct}=="d003", MODE="0666"

Then: sudo udevadm control --reload-rules && sudo udevadm trigger

## Usage

```bash
# Ping the device
./hidxfer --ping

# Read the entire 2 KB of FRAM to a file
./hidxfer -r backup.bin --bytes 2048

# Read 512 bytes starting at offset 1024
./hidxfer -r partial.bin --offset 1024 --bytes 512

# Write a file to FRAM starting at offset 0
./hidxfer -w data.txt

# Write a file to FRAM starting at offset 512
./hidxfer -w data.dat --offset 512
```

## Building the Android editor

```bash
sudo apt update && sudo apt install android-sdk
cd android && make
```

## Building the firmware

Updating the firmware requires a WCH-LinkE or other CH32V003 programmer. The SWIO header is labeled on the board.

```bash
cd fw
make        # builds grunstahl.bin
make flash  # flashes via WCH-LinkE (minichlink)
```

Requires: RISC-V GCC toolchain (riscv-none-embed-gcc or riscv32-unknown-elf-gcc).

## Protocol

```
255-byte HID feature report, report ID 0xaa.

Request (host to device):

  [0]    0xaa   magic
  [1]    0x00   request marker
  [2]    cmd    0x00=PING  0x01=READ  0x02=WRITE
  [3]    addr_lo  FRAM address low byte
  [4]    addr_hi  FRAM address high byte
  [5]    len    payload byte count (max 248)
  [6..]  data   write payload (WRITE only)

Response (device to host, polled until [1] == 0x01):

  [0]    0xaa   magic
  [1]    0x01   response marker
  [2]    status 0x01=ok  0x00=error
  [3..]  data   read payload (READ only)

Transfers larger than 248 bytes are automatically chunked by hidxfer.
```

## LLM-generated code

To the extent that there is LLM-generated code in this repo, it should be space indented. Any space indented code should be carefully audited and then converted to tabs (eventually).

## License

The contents of this repo are released under the [Lone Dynamics Open License](LICENSE.md) with the following exceptions:

  * The ch32fun library is MIT licensed.
  * The hidapi library is BSD licensed.

Note: You can use these designs for commercial purposes but we ask that instead of producing exact clones, that you either replace our trademarks and logos with your own or add your own next to ours.

