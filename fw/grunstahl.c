/*
 * Grünstahl Firmware
 *
 * CH32V003 USB HID device providing read/write access to 2 KB of SPI F-RAM.
 * Based on Einszeit by Lone Dynamics Corporation / CNLohr.
 *
 * Protocol (255-byte HID feature report, report ID 0xaa):
 *
 *   Request  (host → device):
 *     [0]     0xaa        magic
 *     [1]     0x00        direction (request)
 *     [2]     cmd         GS_CMD_*
 *     [3..4]  addr_lo,hi  16-bit FRAM address (little-endian)
 *     [5]     len         payload length in bytes (max 248)
 *     [6..]   data        payload (write only)
 *
 *   Response (device → host):
 *     [0]     0xaa        magic
 *     [1]     0x01        direction (response)
 *     [2]     status      0x01 = ok, 0x00 = error
 *     [3..]   data        payload (read only)
 *
 * Commands:
 *   GS_CMD_PING   0x00   — returns ok, no data
 *   GS_CMD_READ   0x01   — read len bytes from addr → response[3..]
 *   GS_CMD_WRITE  0x02   — write len bytes at addr from request[6..]
 *
 * Pinout:
 *   PC1  FRAM_SS   (SPI chip select, active-low)
 *   PC5  FRAM_SCK  (SPI clock)
 *   PC6  FRAM_MOSI (SPI data out)
 *   PC7  FRAM_MISO (SPI data in)
 *   PD2  LED       (activity, active-high)
 *   PD3  USB D+
 *   PD4  USB D-    (1.5k pull-up via PD5 → Low-Speed)
 *   PD5  USB PU
 */

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include "rv003usb.h"
#include "ch32fun.h"

/* ── Protocol ───────────────────────────────────────────────────────────── */

#define GS_CMD_PING   0x00
#define GS_CMD_READ   0x01
#define GS_CMD_WRITE  0x02

#define GS_MAX_PAYLOAD 248   /* 255 - 7 header bytes */
#define FRAM_SIZE      2048

/* ── Pins ───────────────────────────────────────────────────────────────── */

#define FRAM_SS_PIN   1   /* PC1 */
#define FRAM_SCK_PIN  5   /* PC5 */
#define FRAM_MOSI_PIN 6   /* PC6 */
#define FRAM_MISO_PIN 7   /* PC7 */
#define LED_PIN       2   /* PD2 */

/* ── SPI helpers ────────────────────────────────────────────────────────── */

static inline void ss_low(void)   { GPIOC->BSHR = (1u << (16 + FRAM_SS_PIN)); }
static inline void ss_high(void)  { GPIOC->BSHR = (1u << FRAM_SS_PIN); }
static inline void sck_low(void)  { GPIOC->BSHR = (1u << (16 + FRAM_SCK_PIN)); }
static inline void sck_high(void) { GPIOC->BSHR = (1u << FRAM_SCK_PIN); }
static inline void mosi_set(int b) {
    if (b) GPIOC->BSHR = (1u << FRAM_MOSI_PIN);
    else   GPIOC->BSHR = (1u << (16 + FRAM_MOSI_PIN));
}
static inline int miso_read(void) { return (GPIOC->INDR >> FRAM_MISO_PIN) & 1; }

static uint8_t spi_xfer(uint8_t tx)
{
    uint8_t rx = 0;
    for (int i = 7; i >= 0; i--) {
        mosi_set((tx >> i) & 1);
        sck_high();
        rx = (uint8_t)((rx << 1) | miso_read());
        sck_low();
    }
    return rx;
}

/* ── FRAM driver ────────────────────────────────────────────────────────── */

#define FRAM_OP_WREN  0x06
#define FRAM_OP_READ  0x03
#define FRAM_OP_WRITE 0x02

static void fram_init(void)
{
    RCC->APB2PCENR |= RCC_APB2Periph_GPIOC;

    uint32_t crl = GPIOC->CFGLR;
    /* PC1 SS   output PP 10MHz (bits  7:4) */
    crl &= ~(0xFu <<  4); crl |= (0x1u <<  4);
    /* PC5 SCK  output PP 10MHz (bits 23:20) */
    crl &= ~(0xFu << 20); crl |= (0x1u << 20);
    /* PC6 MOSI output PP 10MHz (bits 27:24) */
    crl &= ~(0xFu << 24); crl |= (0x1u << 24);
    /* PC7 MISO input floating (bits 31:28) */
    crl &= ~(0xFu << 28); crl |= (0x4u << 28);
    GPIOC->CFGLR = crl;

    ss_high();
    sck_low();
}

static void fram_read(uint16_t addr, uint8_t *buf, uint16_t len)
{
    ss_low();
    spi_xfer(FRAM_OP_READ);
    spi_xfer((uint8_t)(addr >> 8));
    spi_xfer((uint8_t)(addr & 0xFF));
    for (uint16_t i = 0; i < len; i++)
        buf[i] = spi_xfer(0x00);
    ss_high();
}

static void fram_write(uint16_t addr, const uint8_t *buf, uint16_t len)
{
    /* WREN — latch clears after each /CS rising edge */
    ss_low(); spi_xfer(FRAM_OP_WREN); ss_high();

    ss_low();
    spi_xfer(FRAM_OP_WRITE);
    spi_xfer((uint8_t)(addr >> 8));
    spi_xfer((uint8_t)(addr & 0xFF));
    for (uint16_t i = 0; i < len; i++)
        spi_xfer(buf[i]);
    ss_high();
}

/* ── LED ────────────────────────────────────────────────────────────────── */

static void led_init(void)
{
    RCC->APB2PCENR |= RCC_APB2Periph_GPIOD;
    GPIOD->CFGLR &= ~(0xFu << (4 * LED_PIN));
    GPIOD->CFGLR |=  (GPIO_Speed_10MHz | GPIO_CNF_OUT_PP) << (4 * LED_PIN);
    GPIOD->BSHR = (1u << (16 + LED_PIN)); /* off */
}

static inline void led_on(void)  { GPIOD->BSHR = (1u << LED_PIN); }
static inline void led_off(void) { GPIOD->BSHR = (1u << (16 + LED_PIN)); }

/* ── Shared HID scratch buffer (same pattern as Einszeit) ───────────────── */

uint8_t scratch[255];
volatile uint8_t start = 0;

/* ── Command handler ────────────────────────────────────────────────────── */

static void handle_command(void)
{
    uint8_t cmd    = scratch[2];
    uint16_t addr  = (uint16_t)(scratch[3] | (scratch[4] << 8));
    uint8_t  len   = scratch[5];

    /* Clamp len to safe maximum and FRAM bounds */
    if (len > GS_MAX_PAYLOAD) len = GS_MAX_PAYLOAD;
    if ((uint32_t)addr + len > FRAM_SIZE) {
        scratch[2] = 0x00; /* error */
        return;
    }

    led_on();

    switch (cmd) {

        case GS_CMD_PING:
            scratch[2] = 0x01;
            break;

        case GS_CMD_READ:
            fram_read(addr, &scratch[3], len);
            scratch[2] = 0x01;
            break;

        case GS_CMD_WRITE:
            fram_write(addr, &scratch[6], len);
            scratch[2] = 0x01;
            break;

        default:
            scratch[2] = 0x00; /* unknown command */
            break;
    }

    led_off();
}

/* ── Main ───────────────────────────────────────────────────────────────── */

int main(void)
{
    SystemInit();
    Delay_Ms(1); /* ensure USB re-enumeration after reset */

    led_init();
    fram_init();

    usb_setup();

    while (1) {
#if RV003USB_EVENT_DEBUGGING
        uint32_t *ue = GetUEvent();
        if (ue) printf("%lu %lx %lx %lx\n", ue[0], ue[1], ue[2], ue[3]);
#endif
        if (start && scratch[0] == 0xaa && scratch[1] == 0x00) {
            handle_command();
            scratch[1] = 0x01; /* mark as response */
            start = 0;
        }
    }
}

/* ── rv003usb HID callbacks (identical pattern to Einszeit) ─────────────── */

void usb_handle_user_in_request(struct usb_endpoint *e, uint8_t *scratchpad,
                                int endp, uint32_t sendtok,
                                struct rv003usb_internal *ist)
{
    if (endp) usb_send_empty(sendtok);
}

void usb_handle_user_data(struct usb_endpoint *e, int current_endpoint,
                          uint8_t *data, int len,
                          struct rv003usb_internal *ist)
{
    int offset = e->count << 3;
    int torx   = e->max_len - offset;
    if (torx > len) torx = len;
    if (torx > 0) {
        memcpy(scratch + offset, data, torx);
        e->count++;
        if ((e->count << 3) >= e->max_len)
            start = e->max_len;
    }
}

void usb_handle_hid_get_report_start(struct usb_endpoint *e, int reqLen,
                                     uint32_t lValueLSBIndexMSB)
{
    if (reqLen > (int)sizeof(scratch)) reqLen = sizeof(scratch);
    e->opaque  = scratch;
    e->max_len = reqLen;
}

void usb_handle_hid_set_report_start(struct usb_endpoint *e, int reqLen,
                                     uint32_t lValueLSBIndexMSB)
{
    if (reqLen > (int)sizeof(scratch)) reqLen = sizeof(scratch);
    e->max_len = reqLen;
}

void usb_handle_other_control_message(struct usb_endpoint *e,
                                      struct usb_urb *s,
                                      struct rv003usb_internal *ist)
{
    LogUEvent(SysTick->CNT, s->wRequestTypeLSBRequestMSB,
              s->lValueLSBIndexMSB, s->wLength);
}
