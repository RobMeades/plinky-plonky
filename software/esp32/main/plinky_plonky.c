/*
 * Copyright 2026 Rob Meades
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/** @file
 * @brief Implementation of a steppper-motor driven plinky-plonky.
 */

#include <string.h>
#include <inttypes.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/queue.h"
#include "esp_event.h"
#include "esp_log.h"
#include "errno.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "esp_timer.h"
#include "esp_task_wdt.h"
#include "nvs.h"
#include "nvs_flash.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "host/ble_gap.h"
#include "host/ble_hs.h"
#include "host/ble_store.h"
#include "host/ble_sm.h"      // For security manager constants
#include "host/util/util.h"

#include "tmc2209.h"

/* ----------------------------------------------------------------
 * COMPILE-TIME MACROS
 * -------------------------------------------------------------- */

 // Logging prefix
 #define TAG "plinky-plonky"

// UART buffer
#define UART_RX_BUFFFER_SIZE 256

// The addresss of the TMC2209 device we are going to us
#define TMC2209_ADDRESS 0

// The number of milliseconds to vTaskDelay() for in order to let the idle task
// to feed its watchdog
 #define WATCHDOG_FEED_TIME_MS 10

// The sense resisitor wired to the BRA and BRB pins of the TMC2209,
// the BigTreeTech board uses 100 milliOhms
#define TMC2209_RSENSE_MOHM 110

// Standard short duration for an LED lash
#define DEBUG_LED_SHORT_MS 50

// Standard short duration for a long LED lash
#define DEBUG_LED_LONG_MS 1000

// The motor current to set, in milliAmps
#define STEPPER_MOTOR_ACTIVE_CURRENT_MA 1000

// The percentage of the run current to apply during hold;
// don't need a lot, let it cool down
#define STEPPER_MOTOR_HOLD_CURRENT_PERCENT 20

// The desired stepper motor velocity
#define STEPPER_MOTOR_DEFAULT_VELOCITY_MILLIHERTZ (1000 * 64 * 7)

// The name that we appear under in Bluetooth
#define BLE_DEVICE_NAME "Plinky-Plonky"

// Service UUID for plinky-plonky control
#define PLINKY_PLONKY_SERVICE_UUID            0xFFE0

// Characteristic UUID for "play" command (Boolean)
#define PLINKY_PLONKY_PLAY_UUID               0xFFE1

// Characteristic UUID for "speed" command (uint32_t)
#define PLINKY_PLONKY_SPEED_MILLIHERTZ_UUID   0xFFE2

// The name for the speed entry in non-volatile storage.
#define NVS_NAME_SPEED "speed"

/* ----------------------------------------------------------------
 * TYPES
 * -------------------------------------------------------------- */

// The context for the plinky-plonky.
typedef struct {
    TaskHandle_t stall_task_handle;
    SemaphoreHandle_t diag_semaphore;
    QueueHandle_t ble_command_queue;
    TaskHandle_t ble_command_task_handle;
    ble_uuid16_t plinky_plonky_service_uuid;
    struct ble_hs_adv_fields ble_adv_fields;
    uint16_t ble_connection_handle;
    bool running;
    bool play_not_stop;
    uint32_t speed_milliHertz;
} plinky_plonky_context_t;

// Plinky-plonky commands.
typedef enum {
    COMMAND_NONE,
    COMMAND_UNKNOWN,
    COMMAND_WRITE_PLAY,
    COMMAND_WRITE_SPEED_MILLIHERTZ,
    COMMAND_READ_PLAY,
    COMMAND_READ_SPEED_MILLIHERTZ
} command_type_t;

// Command queue contents.
typedef struct {
    uint16_t uuid;
    command_type_t type;
    uint32_t value;
} command_t;

/* ----------------------------------------------------------------
 * VARIABLES (THERE ARE MORE FURTHER DOWN)
 * -------------------------------------------------------------- */

// Set up valid advertising parameters.
static const struct ble_gap_adv_params g_ble_adv_params = {
    .conn_mode = BLE_GAP_CONN_MODE_UND,   // Connectable undirected advertising
    .disc_mode = BLE_GAP_DISC_MODE_GEN,   // General discoverable mode
    .itvl_min = 0x20,                     // Minimum advertising interval (20 ms)
    .itvl_max = 0x40};                    // Maximum advertising interval (40 ms)

// Context for the whole application
static plinky_plonky_context_t g_plinky_plonky_context = {
    .stall_task_handle = NULL,
    .diag_semaphore = NULL,
    .ble_command_queue = NULL,
    .ble_command_task_handle = NULL,
    .plinky_plonky_service_uuid = BLE_UUID16_INIT(PLINKY_PLONKY_SERVICE_UUID),
    .ble_adv_fields = {0},
    .ble_connection_handle = BLE_HS_CONN_HANDLE_NONE,
    .running = true,
    .play_not_stop = false,
    .speed_milliHertz = STEPPER_MOTOR_DEFAULT_VELOCITY_MILLIHERTZ};

// THERE ARE MORE VARIABLE FURTHER DOWN

/* ----------------------------------------------------------------
 * STATIC FUNCTIONS: MISC
 * -------------------------------------------------------------- */

// Flash the debug LED
static void flash_debug_led(int32_t duration_ms)
{
#if defined(CONFIG_PLINKY_PLONKY_DEBUG_LED_PIN) && (CONFIG_PLINKY_PLONKY_DEBUG_LED_PIN >= 0)
    gpio_set_level(CONFIG_PLINKY_PLONKY_DEBUG_LED_PIN, 0);
    vTaskDelay(pdMS_TO_TICKS(duration_ms));
    gpio_set_level(CONFIG_PLINKY_PLONKY_DEBUG_LED_PIN, 1);
#endif
}

/* ----------------------------------------------------------------
 * STATIC FUNCTIONS: NVS RELATED
 * -------------------------------------------------------------- */

// Retrieve the stored speed value from NVS, returns
// ESP_ERR_NVS_NOT_FOUND if the value is not initialised.
static esp_err_t nvs_speed_millihertz_get(uint32_t *speed_milliHertz)
{
    nvs_handle_t nvs_handle;

    esp_err_t err = nvs_open("storage", NVS_READONLY, &nvs_handle);
    if (err == ESP_OK) {
        err = nvs_get_u32(nvs_handle, NVS_NAME_SPEED,
                          speed_milliHertz);
        if (err != ESP_OK) {
            ESP_LOGW(TAG, "Unable read \"%s\" from NVS:"
                     " 0x%04x (\"%s\")!", NVS_NAME_SPEED,
                      err, esp_err_to_name(err));
        }
        nvs_close(nvs_handle);
    } else {
        ESP_LOGE(TAG, "Unable to open NVS for read: 0x%04x (\"%s\")!", err, esp_err_to_name(err));
    }

    return err;
}

// Set the stored speed value in NVS.
static esp_err_t nvs_speed_millihertz_set(uint32_t speed_milliHertz)
{
    nvs_handle_t nvs_handle;

    esp_err_t err = nvs_open("storage", NVS_READWRITE, &nvs_handle);
    if (err == ESP_OK) {
        err = nvs_set_u32(nvs_handle, NVS_NAME_SPEED,
                          speed_milliHertz);
        if (err == ESP_OK) {
            err = nvs_commit(nvs_handle);
            if (err != ESP_OK)  {
                ESP_LOGE(TAG, "Unable to commit changes to NVS:"
                        " 0x%04x (\"%s\")!", err, esp_err_to_name(err));
            }
        } else {
            ESP_LOGE(TAG, "Unable write \"%s\" to NVS:"
                     " 0x%04x (\"%s\")!", NVS_NAME_SPEED,
                     err, esp_err_to_name(err));
        }
        nvs_close(nvs_handle);
    } else {
        ESP_LOGE(TAG, "Unable to open NVS for read/write: 0x%04x (\"%s\")!",
                 err, esp_err_to_name(err));
    }

    return err;
}

/* ----------------------------------------------------------------
 * STATIC FUNCTIONS: STEPPER MOTOR RELATED
 * -------------------------------------------------------------- */

#if defined CONFIG_PLINKY_PLONKY_DIAG_PIN && (CONFIG_PLINKY_PLONKY_DIAG_PIN >= 0)

// DIAG interrupt handler,
static void diag_interrupt_handler(void *handler_arg)
{
    plinky_plonky_context_t *context = (plinky_plonky_context_t *) handler_arg;
    BaseType_t higherPriorityTaskWoken;

    if (context->stall_task_handle && context->diag_semaphore) {
        xSemaphoreGiveFromISR(context->diag_semaphore, &higherPriorityTaskWoken);
        portYIELD_FROM_ISR(higherPriorityTaskWoken);
    }
}

#endif

// Task to handle stall indications.
static void stall_task(void *arg)
{
    plinky_plonky_context_t * context = (plinky_plonky_context_t *) arg;

    while (context->running) {
        xSemaphoreTake(context->diag_semaphore, portMAX_DELAY);
        ESP_LOGI(TAG, "STALL");
    }

    vTaskDelete(NULL);
}

/* ----------------------------------------------------------------
 * STATIC FUNCTION PROTOTYPE: NEEDED FOR BLE CALLBACKS
 * -------------------------------------------------------------- */

static int ble_gap_event_callback(struct ble_gap_event *event, void *arg);

/* ----------------------------------------------------------------
 * STATIC FUNCTIONS: BLE
 * -------------------------------------------------------------- */

// Task to handle BLE commands.
static void ble_command_task(void *arg)
{
    plinky_plonky_context_t * context = (plinky_plonky_context_t *) arg;
    command_t command;

    while (context->running) {
        if (xQueueReceive(context->ble_command_queue, &command, pdMS_TO_TICKS(100))) {
            switch (command.type) {
                case COMMAND_WRITE_PLAY:
                    context->play_not_stop = command.value;
                    if (context->play_not_stop) {
                        tmc2209_set_velocity(TMC2209_ADDRESS, context->speed_milliHertz);
                        tmc2209_motor_enable(TMC2209_ADDRESS);
                    } else {
                        tmc2209_motor_disable(TMC2209_ADDRESS);
                    }
                    ESP_LOGI(TAG, "BLE command (0x%04x), write: %s.",
                             command.uuid, context->play_not_stop ? "play" : "stop");
                    break;
                case COMMAND_WRITE_SPEED_MILLIHERTZ:
                    context->speed_milliHertz = command.value;
                    nvs_speed_millihertz_set(context->speed_milliHertz);
                    ESP_LOGI(TAG, "BLE command (0x%04x), write: speed %lu milliHertz.",
                             command.uuid, context->speed_milliHertz);
                    break;
                case COMMAND_READ_PLAY:
                    ESP_LOGI(TAG, "BLE command (0x%04x), read: %s.",
                             command.uuid, context->play_not_stop ? "play" : "stop");
                    break;
                case COMMAND_READ_SPEED_MILLIHERTZ:
                    ESP_LOGI(TAG, "BLE command (0x%04x), read: speed %lu milliHertz.",
                             command.uuid, context->speed_milliHertz);
                    break;
                case COMMAND_UNKNOWN:
                    ESP_LOGI(TAG, "BLE command (0x%04x) received unknown operation: %d (0x08x).",
                             command.uuid, command.value, command.value);
                    break;
                case COMMAND_NONE:
                default:
                    break;
             }
        }
        vTaskDelay(pdMS_TO_TICKS(WATCHDOG_FEED_TIME_MS));
    }

    vTaskDelete(NULL);
}

// Play/Stop callback
static int plinky_plonky_play_callback(uint16_t conn_handle, uint16_t attr_handle,
                                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    plinky_plonky_context_t *context = (plinky_plonky_context_t *) arg;
    int return_code = BLE_ATT_ERR_UNLIKELY;

    switch (ctxt->op) {
        case BLE_GATT_ACCESS_OP_WRITE_CHR:
        {
            // Read four bytes, just in case an int32_t is sent
            uint32_t value;
            return_code = BLE_ATT_ERR_INSUFFICIENT_RES;
            size_t len = os_mbuf_copydata(ctxt->om, 0, sizeof(value), &value);
            if (len >= 1) {
                // Queue the command (non-blocking)
                command_t command = {.uuid = PLINKY_PLONKY_PLAY_UUID,
                                     .type = COMMAND_WRITE_PLAY,
                                     .value = value};
                xQueueSendFromISR(context->ble_command_queue, &command, NULL);
                // Send empty success response
                os_mbuf_free_chain(ctxt->om);
                ctxt->om = ble_hs_mbuf_from_flat("", 0);
                return_code = 0;
            }
        }
            break;
        case BLE_GATT_ACCESS_OP_READ_CHR:
        {
            // Queue the command (non-blocking), purely for information
            command_t command = {.uuid = PLINKY_PLONKY_PLAY_UUID,
                                 .type = COMMAND_READ_PLAY,
                                 .value = 0};
            xQueueSendFromISR(context->ble_command_queue, &command, NULL);

            // Send the current setting
            os_mbuf_append(ctxt->om, &context->play_not_stop, sizeof(context->play_not_stop));
            return_code = 0;
        }
            break;
        default:
        {
            // Queue the command (non-blocking), for information
            command_t command = {.uuid = PLINKY_PLONKY_PLAY_UUID,
                                 .type = COMMAND_UNKNOWN,
                                 .value = ctxt->op};
            xQueueSendFromISR(context->ble_command_queue, &command, NULL);
        }
            break;
    }

    return return_code;
}

// Speed callback.
static int plinky_plonky_speed_callback(uint16_t conn_handle, uint16_t attr_handle,
                                        struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    plinky_plonky_context_t *context = (plinky_plonky_context_t *) arg;
    int return_code = BLE_ATT_ERR_UNLIKELY;

    switch (ctxt->op) {
        case BLE_GATT_ACCESS_OP_WRITE_CHR:
        {
            // Read the 4-byte uint32_t value (little-endian)
            uint32_t value;
            return_code = BLE_ATT_ERR_INSUFFICIENT_RES;
            size_t len = os_mbuf_copydata(ctxt->om, 0, sizeof(value), &value);
            if (len >= sizeof(value)) {
                // Queue the command (non-blocking)
                command_t command = {.uuid = PLINKY_PLONKY_SPEED_MILLIHERTZ_UUID,
                                     .type = COMMAND_WRITE_SPEED_MILLIHERTZ,
                                     .value = value};
                xQueueSendFromISR(context->ble_command_queue, &command, NULL);

                // Send empty success response
                os_mbuf_free_chain(ctxt->om);
                ctxt->om = ble_hs_mbuf_from_flat("", 0);
                return_code = 0;
            }
        }
            break;
        case BLE_GATT_ACCESS_OP_READ_CHR:
        {
            // Queue the command (non-blocking), purely for information
            command_t command = {.uuid = PLINKY_PLONKY_SPEED_MILLIHERTZ_UUID,
                                 .type = COMMAND_READ_SPEED_MILLIHERTZ,
                                 .value = 0};
            xQueueSendFromISR(context->ble_command_queue, &command, NULL);

            // Send the current speed value
            uint32_t speed = context->speed_milliHertz;
            os_mbuf_append(ctxt->om, &speed, sizeof(speed));
            return_code = 0;
        }
            break;
        default:
        {
            // Queue the command (non-blocking), for information
            command_t command = {.uuid = PLINKY_PLONKY_SPEED_MILLIHERTZ_UUID,
                                 .type = COMMAND_UNKNOWN,
                                 .value = ctxt->op};
            xQueueSendFromISR(context->ble_command_queue, &command, NULL);
        }
            break;
    }

    return return_code;
}

// Start BLE advertising
static int32_t ble_start_advertising(plinky_plonky_context_t *context)
{
    ESP_LOGI(TAG, "BLE (re)starting advertising.");

    // Set flags
    context->ble_adv_fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;

    // Set service UUID
    context->ble_adv_fields.uuids16 = &context->plinky_plonky_service_uuid;
    context->ble_adv_fields.num_uuids16 = 1;
    context->ble_adv_fields.uuids16_is_complete = 1;

    // Optional: Set device name
    context->ble_adv_fields.name = (uint8_t *)BLE_DEVICE_NAME;
    context->ble_adv_fields.name_len = strlen(BLE_DEVICE_NAME);
    context->ble_adv_fields.name_is_complete = 1;

    int32_t err = ble_gap_adv_set_fields(&context->ble_adv_fields);
    if (err == 0) {
        err = ble_gap_adv_start(0, NULL, BLE_HS_FOREVER, &g_ble_adv_params,
                                ble_gap_event_callback, context);
        if ((err != 0) && (err != BLE_HS_EALREADY)) {
            ESP_LOGE(TAG, "BLE failed to start advertising: %d (%d)!", err, BLE_HS_EALREADY);
        }
    } else {
        ESP_LOGE(TAG, "BLE failed to set advertisement fields: %d!", err);
    }

    return err;
}

// GAP event callback, handles connections.
static int ble_gap_event_callback(struct ble_gap_event *event, void *arg)
{
    plinky_plonky_context_t *context = (plinky_plonky_context_t *) arg;

    switch (event->type) {
        case BLE_GAP_EVENT_CONNECT:
        {
            ESP_LOGI(TAG, "BLE_GAP_EVENT_CONNECT, status: %d.", event->connect.status);
            if (event->connect.status == 0) {
                context->ble_connection_handle = event->connect.conn_handle;
                ESP_LOGI(TAG, "Device connected successfully.");
            } else {
                ESP_LOGE(TAG, "Connection failed with status: %d!", event->connect.status);

                // A small delay before restarting advertising,
                // without this, NimBLE can get stuck in a bad state
                vTaskDelay(pdMS_TO_TICKS(200));
                ble_start_advertising(context);
            }
        }
            break;
        case BLE_GAP_EVENT_DISCONNECT:
        {
            ESP_LOGI(TAG, "BLE_GAP_EVENT_DISCONNECT, reason: %d.", event->disconnect.reason);
            context->ble_connection_handle = BLE_HS_CONN_HANDLE_NONE;

            // A small delay before restarting advertising,
            // without this, NimBLE can get stuck in a bad state
            vTaskDelay(pdMS_TO_TICKS(200));
            ble_start_advertising(context);
        }
            break;

        case BLE_GAP_EVENT_ADV_COMPLETE:
        {
            ESP_LOGI(TAG, "BLE_GAP_EVENT_ADV_COMPLETE.");
        }
            break;

        default:
            break;
    }

    return 0;
}

// Called by BLE task when it has sorted itself out.
static void ble_on_sync_callback(void)
{
    int32_t err = ble_hs_util_ensure_addr(0);  // Use public address (0 = public)
    if (err == 0) {
        // Start BLE advertising
        err = ble_start_advertising(&g_plinky_plonky_context);
        if (err == 0) {
            // Print MAC address for info
            uint8_t ble_address_type;
            int32_t err = ble_hs_id_infer_auto(0, &ble_address_type);
            if (err == 0) {
                uint8_t address[6] = {0};
                err = ble_hs_id_copy_addr(ble_address_type, address, NULL);
                if (err == 0) {
                    ESP_LOGI(TAG, "BLE MAC address: %02x:%02x:%02x:%02x:%02x:%02x",
                            address[0], address[1], address[2], address[3], address[4], address[5]);
                }
            }

            ESP_LOGI(TAG, "BLE ready, device name \"%s\".", BLE_DEVICE_NAME);
            ESP_LOGI(TAG, "BLE pair with device and connect using any Bluetooth terminal app.");
        }
    } else {
        ESP_LOGE(TAG, "BLE failed to ensure public address: %d", err);
    }
}

// Called by BLE task on a reset.
static void ble_on_reset_callback(int reason)
{
    ESP_LOGW(TAG, "\nBLE resetting state (%d).", reason);
}

// Wot it says.
static void ble_task(void *param)
{
    ESP_LOGI(TAG, "BLE host task started.");

    // This function will return only when nimble_port_stop() is executed
    nimble_port_run();

    nimble_port_freertos_deinit();
}

/* ----------------------------------------------------------------
 * VARIABLES FOR BLE
 * -------------------------------------------------------------- */

// BLE service setup.
static const struct ble_gatt_svc_def g_ble_plinky_plonky_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(PLINKY_PLONKY_SERVICE_UUID),
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                // Play/Stop characteristic (WRITE only)
                .uuid = BLE_UUID16_DECLARE(PLINKY_PLONKY_PLAY_UUID),
                .access_cb = plinky_plonky_play_callback,
                .arg = &g_plinky_plonky_context,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
            },
            {
                // Speed characteristic (READ/WRITE)
                .uuid = BLE_UUID16_DECLARE(PLINKY_PLONKY_SPEED_MILLIHERTZ_UUID),
                .access_cb = plinky_plonky_speed_callback,
                .arg = &g_plinky_plonky_context,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
            },
            {0}  // Terminator
        },
    },
    {0}  // Service terminator
};

/* ----------------------------------------------------------------
 * STATIC FUNCTIONS: INITIALISATION
 * -------------------------------------------------------------- */

// Initialisation.
static esp_err_t init(plinky_plonky_context_t *context)
{
#if defined(CONFIG_PLINKY_PLONKY_ENABLE_PIN) && (CONFIG_PLINKY_PLONKY_ENABLE_PIN >= 0)
    // Stop the motor moving too much before it is configured,
    if (gpio_set_level(CONFIG_PLINKY_PLONKY_ENABLE_PIN, 1) == ESP_OK) {
        gpio_set_direction(CONFIG_PLINKY_PLONKY_ENABLE_PIN, GPIO_MODE_OUTPUT);
    }
#endif

    // Create the default event loop, for everyone's use
    esp_err_t err = esp_event_loop_create_default();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to create default event loop: 0x%04x (\"%s\")!", err, esp_err_to_name(err));
    }

#if defined(CONFIG_PLINKY_PLONKY_DEBUG_LED_PIN) && (CONFIG_PLINKY_PLONKY_DEBUG_LED_PIN >= 0)
    // Configure our debug LED
    if (err == ESP_OK) {
        err = gpio_set_level(CONFIG_PLINKY_PLONKY_DEBUG_LED_PIN, 1);
        if (err == ESP_OK) {
            err = gpio_set_direction(CONFIG_PLINKY_PLONKY_DEBUG_LED_PIN, GPIO_MODE_OUTPUT);
            if (err == ESP_OK) {
                // Flash it so that we know it can be active
                flash_debug_led(DEBUG_LED_SHORT_MS);
            }
        }
    }
#endif

    // BLE requires non-volatile storage
    err = nvs_flash_init();
    if ((err == ESP_ERR_NVS_NO_FREE_PAGES) || (err == ESP_ERR_NVS_NEW_VERSION_FOUND)) {
        // Erase NVS partition and initialize NVS again.
        esp_err_t erase_err = nvs_flash_erase();
        if (erase_err == ESP_OK) {
            err = nvs_flash_init();
        } else {
            ESP_LOGE(TAG, "Failed to erase NVS: 0x%04x (\"%s\")!", erase_err, esp_err_to_name(erase_err));
        }
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Failed to initialize NVS: 0x%04x (\"%s\")!", err, esp_err_to_name(err));
    } else {
         err = nvs_speed_millihertz_get(&context->speed_milliHertz);
         if (err == ESP_OK) {
             ESP_LOGI(TAG, "Read speed %d milliHertz from flash.", context->speed_milliHertz);
         } else {
            if (err == ESP_ERR_NVS_NOT_FOUND) {
                ESP_LOGI(TAG, "Unable to read speed from flash, writing default"
                         " (%d milliHertz).", context->speed_milliHertz);
                // Flash must have been erased, write the default
                err = nvs_speed_millihertz_set(context->speed_milliHertz);
            }
         }
    }
    if (err != ESP_OK) {
        // Continue anyway
        ESP_LOGW(TAG, "Not able to maintain speed value in flash: 0x%04x (\"%s\")!",
                 err, esp_err_to_name(err));
        err = ESP_OK;
    }

    // Initialize the TMC2209 stepper motor driver interface
    if (err == ESP_OK) {
        err = tmc2209_init(CONFIG_PLINKY_PLONKY_UART_NUM, CONFIG_PLINKY_PLONKY_UART_TXD_PIN,
                           CONFIG_PLINKY_PLONKY_UART_RXD_PIN, CONFIG_PLINKY_PLONKY_UART_BAUD_RATE);
    }

    // Create the RTOS stuff needed for stall handling
    if (err == ESP_OK) {
        vSemaphoreCreateBinary(context->diag_semaphore);
        if (!context->diag_semaphore ||
            (xTaskCreate(&stall_task, "stall_task", 1024 * 4, context, 5, &context->stall_task_handle) != pdPASS)) {
            err = ESP_ERR_NO_MEM;
            ESP_LOGE(TAG, "Unable to create stall_task or semaphore.");
        }
#if defined CONFIG_PLINKY_PLONKY_DIAG_PIN && (CONFIG_PLINKY_PLONKY_DIAG_PIN >= 0)
        // Initial setup of stall detection with threshold value that means
        // a stall should never be detected
        if (err == ESP_OK) {
            tmc2209_init_stallguard(TMC2209_ADDRESS, -1, 100, CONFIG_PLINKY_PLONKY_DIAG_PIN,
                                    diag_interrupt_handler, context);
        }
#endif
    }

    if (err == ESP_OK) {
        // RTOS stuff needed for BLE
        context->ble_command_queue = xQueueCreate(10, sizeof(command_t));
        if (!context->ble_command_queue||
            xTaskCreate(&ble_command_task, "ble_command", 4096, context, 3, &context->ble_command_task_handle) != pdPASS) {
            err = ESP_ERR_NO_MEM;
            ESP_LOGE(TAG, "Unable to create ble_command task or queue.");
        }
    }

    if (err == ESP_OK) {
        ESP_LOGI(TAG, "BLE starting.");
        // Initialize NimBLE (ESP32-C3's BLE stack)
        err = nimble_port_init();
        if (err != 0) {
            ESP_LOGE(TAG, "BLE failed to initialise NimBLE: %d!", err);
        }
    }

    if (err == 0) {
        // Initialise the NimBLE host configuration, noting that
        // ble_hs_cfg is a magic local variable exported by host/ble_hs.h
        ble_hs_cfg.sync_cb = ble_on_sync_callback;
        ble_hs_cfg.reset_cb = ble_on_reset_callback;

        // Enable bonding and security
        ble_hs_cfg.sm_bonding = 1;
        ble_hs_cfg.sm_mitm = 1;          // Man-In-The-Middle protection
        ble_hs_cfg.sm_sc = 1;            // Secure Connections
        ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;  // Use NO_IO for now

        // Distribute BOTH Encryption and Identity keys
        ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
        ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;

        // Initialise the mandatory Generic Access Profile service (0x1800)
        ble_svc_gap_init();
        // Initialise the mandatory Generic ATTribute service (0x1801)
        ble_svc_gatt_init();
        // [optional] add our device name
        err = ble_svc_gap_device_name_set(BLE_DEVICE_NAME);
        if (err != 0) {
            ESP_LOGE(TAG, "BLE failed to initialise device name (\"%s\"): %d!",
                     BLE_DEVICE_NAME, err);
        }
    }

    if (err == 0) {
        // Set up _our_ Generic ATTribue service
        err = ble_gatts_count_cfg(g_ble_plinky_plonky_svcs);
        if (err == 0) {
            err = ble_gatts_add_svcs(g_ble_plinky_plonky_svcs);
            if (err != 0) {
                ESP_LOGE(TAG, "BLE failed to add our GATT service: %d!", err);
            }
        } else {
            ESP_LOGE(TAG, "BLE failed to configure GATT count: %d!", err);
        }
    }

    if (err == 0) {
        // Start the BLE task
        nimble_port_freertos_init(ble_task);
    }

    return err;
}

/* ----------------------------------------------------------------
 * PUBLIC FUNCTIONS
 * -------------------------------------------------------------- */

// Entry point
void app_main(void)
{
    plinky_plonky_context_t *context = &g_plinky_plonky_context;

    ESP_LOGI(TAG, "Plinky-plonky app_main start");

    esp_err_t err = init(context);
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "Initialization complete.");

        err = tmc2209_start(TMC2209_ADDRESS, CONFIG_PLINKY_PLONKY_ENABLE_PIN);

        if (err == ESP_OK) {
            ESP_LOGI(TAG, "Setting TMC2209 %d to full step.", TMC2209_ADDRESS);
            err = tmc2209_set_microstep_resolution(TMC2209_ADDRESS, 1);
            if (err >= 0) {
                ESP_LOGI(TAG, "Microstep resolution is now %d.", err);
                err = ESP_OK;
            }
        }

#if defined CONFIG_PLINKY_PLONKY_DIAG_PIN && (CONFIG_PLINKY_PLONKY_DIAG_PIN >= 0)
        if (err == ESP_OK) {
            tmc2209_set_stallguard(TMC2209_ADDRESS, -1, 0);
        }
#endif

        // Allow us to feed the watchdog
        esp_task_wdt_add(NULL);

        if (err == ESP_OK) {
            ESP_LOGI(TAG, "Setting motor current to %d mA.", STEPPER_MOTOR_ACTIVE_CURRENT_MA);
            err = tmc2209_set_current(TMC2209_ADDRESS, TMC2209_RSENSE_MOHM,
                                      STEPPER_MOTOR_ACTIVE_CURRENT_MA,
                                      STEPPER_MOTOR_HOLD_CURRENT_PERCENT);
            if (err >= 0) {
                ESP_LOGI(TAG, "Current set to %d mA.", err);
                err = ESP_OK;
            }
        }

        while (context->running) {
            // Let BLE commands do their thing
            vTaskDelay(pdMS_TO_TICKS(1000));
            esp_task_wdt_reset();
        }
        esp_task_wdt_delete(NULL);

    } else {
        ESP_LOGE(TAG, "Initialization failed, system cannot continue, will restart soonish.");
        vTaskDelay(pdMS_TO_TICKS(2000));
    }

    // Setting this will cause the tasks to exit
    g_plinky_plonky_context.running = false;

#if defined CONFIG_PLINKY_PLONKY_DIAG_PIN && (CONFIG_PLINKY_PLONKY_DIAG_PIN >= 0)
    tmc2209_deinit_stallguard(CONFIG_PLINKY_PLONKY_DIAG_PIN);
#endif
    if (context->ble_command_queue){
        vQueueDelete(context->ble_command_queue);
    }
    if (context->diag_semaphore) {
        vSemaphoreDelete(context->diag_semaphore);
    }
    nimble_port_stop();
    tmc2209_deinit();
    esp_restart();
}

// End of file
