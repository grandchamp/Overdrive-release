package com.overdrive.app.mqtt;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.logging.DaemonLogger;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home Assistant MQTT auto-discovery integration.
 *
 * Publishes retained discovery configs so HA creates entities automatically,
 * subscribes to command topics, and routes incoming commands to the BYD HAL
 * or cloud client.
 *
 * Lifecycle:
 *   onConnected(client)    — call after the Paho client connects
 *   onDisconnected(client) — call before the Paho client disconnects
 *   onMessage(topic, payload) — call from MqttCallback.messageArrived
 */
class HaIntegration {

    private static final String TAG = "MqttHaIntegration";

    @FunctionalInterface
    private interface CommandHandler {
        void execute(String payload) throws Exception;
    }

    private final MqttConnectionConfig config;
    private final String deviceId;
    private final DaemonLogger logger;
    private final Map<String, CommandHandler> commandMap;
    private volatile boolean discoveryPublished = false;
    private final List<String> discoveryTopics = Collections.synchronizedList(new ArrayList<>());

    HaIntegration(MqttConnectionConfig config, String deviceId) {
        this.config = config;
        this.deviceId = deviceId;
        this.logger = DaemonLogger.getInstance(TAG);
        this.commandMap = buildCommandMap();
    }

    // ==================== LIFECYCLE ====================

    void onConnected(MqttClient client) {
        new Thread(() -> {
            try {
                boolean cloudEnabled = checkCloudAvailable();
                publishDiscovery(client, cloudEnabled);
                subscribeCommandTopics(client);
            } catch (Exception e) {
                logger.warn("HA: setup failed: " + e.getMessage());
            }
        }, "HA-Setup-" + config.id).start();
    }

    void onDisconnected(MqttClient client) {
        if (discoveryPublished) {
            clearDiscovery(client);
            discoveryPublished = false;
        }
    }

    void onMessage(String topic, String payload) {
        logger.info("HA: message received topic=" + topic + " payload=" + payload);
        String prefix = config.topic + "/command/";
        if (!topic.startsWith(prefix)) {
            logger.warn("HA: ignoring message — topic doesn't match prefix " + prefix);
            return;
        }
        String suffix = topic.substring(prefix.length());
        logger.info("HA: routing command suffix=" + suffix);
        new Thread(() -> routeCommand(suffix, payload), "HA-Cmd-" + config.id).start();
    }

    // ==================== SETUP ====================

    private boolean checkCloudAvailable() {
        try {
            BydCloudConfig cloudConfig = BydCloudConfig.fromUnifiedConfig();
            return cloudConfig.isConfigured() && cloudConfig.enabled;
        } catch (Exception e) {
            return false;
        }
    }

    private void subscribeCommandTopics(MqttClient client) {
        try {
            String cmdTopic = config.topic + "/command/#";
            client.subscribe(cmdTopic, 1);
            logger.info("HA: subscribed to " + cmdTopic);
        } catch (Exception e) {
            logger.warn("HA: subscribe failed: " + e.getMessage());
        }
    }

    // ==================== DISCOVERY ====================

    private void publishDiscovery(MqttClient client, boolean cloudEnabled) {
        // Reset on each republish — the broker overwrites retained configs at the same
        // topic names, but our local tracking list needs to reflect the current set,
        // not the cumulative one across reconnects.
        discoveryTopics.clear();

        String state = config.topic;
        String avail = config.topic + "/availability";
        String cmd   = config.topic + "/command/";
        String uid   = sanitize(deviceId);
        JSONObject dev = buildHaDevice(uid);

        // Read-only sensors
        pub(client, "sensor", uid + "_soc",          haSensor(dev, "State of Charge",     state, "soc",                   avail, "battery",          "%"));
        pub(client, "sensor", uid + "_power",         haSensor(dev, "Power",               state, "power",                 avail, "power",             "kW"));
        pub(client, "sensor", uid + "_speed",         haSensor(dev, "Speed",               state, "speed",                 avail, "speed",             "km/h"));
        pub(client, "sensor", uid + "_ev_range",      haSensor(dev, "EV Range",            state, "ev_range_km",           avail, "distance",          "km"));
        pub(client, "sensor", uid + "_odometer",      haSensor(dev, "Odometer",            state, "odometer",              avail, "distance",          "km"));
        pub(client, "sensor", uid + "_batt_temp",     haSensor(dev, "Battery Temperature", state, "batt_temp",             avail, "temperature",       "°C"));
        pub(client, "sensor", uid + "_ext_temp",      haSensor(dev, "Outside Temperature", state, "ext_temp",              avail, "temperature",       "°C"));
        pub(client, "sensor", uid + "_cabin_temp",    haSensor(dev, "Cabin Temperature",   state, "cabin_temp",            avail, "temperature",       "°C"));
        pub(client, "sensor", uid + "_soh",           haSensor(dev, "State of Health",     state, "soh",                   avail, null,                "%"));
        pub(client, "sensor", uid + "_capacity",      haSensor(dev, "Battery Remaining",   state, "capacity",              avail, "energy",            "kWh"));
        pub(client, "sensor", uid + "_consumption",   haSensor(dev, "Consumption 50km",    state, "consumption_50km",      avail, null,                "kWh/100km"));
        pub(client, "sensor", uid + "_trip_kwh",      haSensor(dev, "Trip Energy",         state, "trip_kwh",              avail, "energy",            "kWh"));
        pub(client, "sensor", uid + "_trip_km",       haSensor(dev, "Trip Distance",       state, "trip_km",               avail, "distance",          "km"));
        pub(client, "sensor", uid + "_eta_h",         haSensor(dev, "Charging ETA (h)",    state, "charging_eta_hours",    avail, "duration",          "h"));
        pub(client, "sensor", uid + "_eta_min",       haSensor(dev, "Charging ETA (min)",  state, "charging_eta_minutes",  avail, "duration",          "min"));
        pub(client, "sensor", uid + "_elevation",     haSensor(dev, "Elevation",           state, "elevation",             avail, null,                "m"));
        pub(client, "sensor", uid + "_heading",       haSensor(dev, "Heading",             state, "heading",               avail, null,                "°"));
        pub(client, "sensor", uid + "_lat",           haSensor(dev, "Latitude",            state, "lat",                   avail, null,                "°"));
        pub(client, "sensor", uid + "_lon",           haSensor(dev, "Longitude",           state, "lon",                   avail, null,                "°"));
        pub(client, "sensor", uid + "_key_battery",   haSensor(dev, "Key Battery",         state, "key_battery",           avail, "battery",           null));
        pub(client, "sensor", uid + "_gear",          haSensor(dev, "Gear",                state, "gear",                  avail, null,                null));

        // Binary sensors
        pub(client, "binary_sensor", uid + "_charging", haBinarySensor(dev, "Charging",         state, "is_charging", avail, "battery_charging"));
        pub(client, "binary_sensor", uid + "_dcfc",     haBinarySensor(dev, "DC Fast Charging", state, "is_dcfc",     avail, "battery_charging"));
        pub(client, "binary_sensor", uid + "_parked",   haBinarySensor(dev, "Parked",           state, "is_parked",   avail, "occupancy"));

        // Window covers (position 0–100)
        pub(client, "cover", uid + "_window_lf",    haWindowCover(dev, "Window Front Left",  state, "window_lf_pct",  avail, cmd + "window/lf"));
        pub(client, "cover", uid + "_window_rf",    haWindowCover(dev, "Window Front Right", state, "window_rf_pct",  avail, cmd + "window/rf"));
        pub(client, "cover", uid + "_window_lr",    haWindowCover(dev, "Window Rear Left",   state, "window_lr_pct",  avail, cmd + "window/lr"));
        pub(client, "cover", uid + "_window_rr",    haWindowCover(dev, "Window Rear Right",  state, "window_rr_pct",  avail, cmd + "window/rr"));
        pub(client, "cover", uid + "_sunroof",      haWindowCover(dev, "Sunroof",            state, "sunroof_pct",    avail, cmd + "window/sunroof"));
        pub(client, "cover", uid + "_sunshade",     haWindowCover(dev, "Sunshade",           state, "sunshade_pct",   avail, cmd + "window/sunshade"));

        // Trunk cover (open/close, no position)
        pub(client, "cover", uid + "_trunk", haTrunkCover(dev, state, avail, cmd + "trunk"));

        // AC switch (has state)
        pub(client, "switch", uid + "_ac", haAcSwitch(dev, state, avail, cmd + "climate/power"));

        // AC fan number (has state)
        pub(client, "number", uid + "_ac_fan", haNumber(dev, "AC Fan Level", state, "ac_fan", avail, cmd + "climate/fan", 1, 7, 1, false));

        // Seat heat / vent selects (optimistic — no read-back from HAL)
        String[] levels = {"OFF", "LOW", "MEDIUM", "HIGH"};
        pub(client, "select", uid + "_seat_heat_driver", haSelect(dev, "Seat Heat Driver",    cmd + "seat/driver/heat", levels));
        pub(client, "select", uid + "_seat_heat_pass",   haSelect(dev, "Seat Heat Passenger", cmd + "seat/pass/heat",   levels));
        pub(client, "select", uid + "_seat_vent_driver", haSelect(dev, "Seat Vent Driver",    cmd + "seat/driver/vent", levels));
        pub(client, "select", uid + "_seat_vent_pass",   haSelect(dev, "Seat Vent Passenger", cmd + "seat/pass/vent",   levels));

        // Charge stop (optimistic)
        pub(client, "number", uid + "_charge_stop", haNumber(dev, "Charge Stop", null, null, null, cmd + "charge/stop", 50, 100, 5, true));

        // Ambient light (optimistic)
        pub(client, "switch",  uid + "_ambient",            haOptimisticSwitch(dev, "Ambient Light",   cmd + "ambient/power"));
        pub(client, "number",  uid + "_ambient_brightness", haNumber(dev, "Ambient Brightness", null, null, null, cmd + "ambient/brightness", 0, 100, 1, true));

        // Cloud-only entities
        if (cloudEnabled) {
            pub(client, "lock",   uid + "_lock",  haLock(dev, cmd + "lock"));
            pub(client, "button", uid + "_flash", haButton(dev, "Flash Lights", cmd + "flash"));
        }

        discoveryPublished = true;
        logger.info("HA: published " + discoveryTopics.size() + " discovery configs (cloud=" + cloudEnabled + ")");
    }

    private void pub(MqttClient client, String component, String objectId, JSONObject cfg) {
        String topic = "homeassistant/" + component + "/" + objectId + "/config";
        discoveryTopics.add(topic);
        try {
            // unique_id is REQUIRED by HA to associate entities with a device.
            // Without it, entities show up unattached or get silently rejected.
            cfg.put("unique_id", objectId);
            MqttMessage msg = new MqttMessage(cfg.toString().getBytes("UTF-8"));
            msg.setQos(1);
            msg.setRetained(true);
            client.publish(topic, msg);
        } catch (Exception e) {
            logger.warn("HA: failed to publish " + topic + ": " + e.getMessage());
        }
    }

    private void clearDiscovery(MqttClient client) {
        if (client == null || !client.isConnected()) return;
        for (String topic : discoveryTopics) {
            try {
                MqttMessage empty = new MqttMessage(new byte[0]);
                empty.setRetained(true);
                empty.setQos(1);
                client.publish(topic, empty);
            } catch (Exception e) {
                logger.warn("HA: failed to clear " + topic);
            }
        }
        discoveryTopics.clear();
        logger.info("HA: cleared discovery configs");
    }

    // ==================== COMMAND ROUTING ====================

    private Map<String, CommandHandler> buildCommandMap() {
        Map<String, CommandHandler> map = new HashMap<>();

        // Windows: accepts a numeric position (0–100) or OPEN/CLOSE/STOP
        String[] windowKeys  = {"lf", "rf", "lr", "rr", "sunroof", "sunshade"};
        int[]    windowAreas = { 1,    2,    3,    4,    5,         6};
        for (int i = 0; i < windowKeys.length; i++) {
            final int area = windowAreas[i];
            map.put("window/" + windowKeys[i], payload -> {
                BydDataCollector col = BydDataCollector.getInstance();
                try {
                    col.moveWindowToPercent(area, Integer.parseInt(payload.trim()));
                } catch (NumberFormatException e) {
                    int cmd = "OPEN".equalsIgnoreCase(payload.trim())  ? 1
                            : "CLOSE".equalsIgnoreCase(payload.trim()) ? 2 : 3;
                    col.setWindowCommand(area, cmd);
                }
            });
        }

        // Trunk: OPEN requires cloud unlock first (same safety requirement as the vehicle control page)
        map.put("trunk", payload -> {
            BydDataCollector col = BydDataCollector.getInstance();
            switch (payload.trim().toUpperCase()) {
                case "OPEN":  openTrunkSafely(); break;
                case "CLOSE": col.closeTailgate(); break;
                default:      col.stopTailgate();  break;
            }
        });

        // Climate
        map.put("climate/power", payload ->
            BydDataCollector.getInstance().setAcPower("ON".equalsIgnoreCase(payload.trim())));
        map.put("climate/fan", payload ->
            BydDataCollector.getInstance().setAcFanLevel(Integer.parseInt(payload.trim())));

        // Seats: heat + vent for driver (1) and passenger (2)
        String[] seatKeys      = {"driver", "pass"};
        int[]    seatPositions = { 1,        2};
        for (int i = 0; i < seatKeys.length; i++) {
            final int pos = seatPositions[i];
            map.put("seat/" + seatKeys[i] + "/heat", payload ->
                BydDataCollector.getInstance().setSeatHeating(pos, seatLevel(payload)));
            map.put("seat/" + seatKeys[i] + "/vent", payload ->
                BydDataCollector.getInstance().setSeatVentilation(pos, seatLevel(payload)));
        }

        // Charge stop capacity
        map.put("charge/stop", payload ->
            BydDataCollector.getInstance().setChargeStopCapacity(Integer.parseInt(payload.trim())));

        // Ambient light
        map.put("ambient/power", payload ->
            BydDataCollector.getInstance().setAmbientLightEnabled("ON".equalsIgnoreCase(payload.trim())));
        map.put("ambient/brightness", payload ->
            BydDataCollector.getInstance().setAmbientBrightness(Integer.parseInt(payload.trim())));
        map.put("ambient/color", payload ->
            BydDataCollector.getInstance().setAmbientColor(Integer.parseInt(payload.trim())));

        // Cloud: lock/unlock (single topic, payload distinguishes action) and flash
        map.put("lock", payload -> {
            BydCloudClient cloudClient = getCloudClient();
            String vin = getVin();
            if ("LOCK".equalsIgnoreCase(payload.trim()))        cloudClient.lock(vin);
            else if ("UNLOCK".equalsIgnoreCase(payload.trim())) cloudClient.unlock(vin);
        });
        map.put("flash", payload -> getCloudClient().flashLightsNoWait(getVin()));

        return map;
    }

    private void routeCommand(String suffix, String payload) {
        CommandHandler handler = commandMap.get(suffix);
        if (handler == null) {
            logger.warn("HA: no handler for command: " + suffix);
            return;
        }
        try {
            handler.execute(payload);
            logger.info("HA: command executed (" + suffix + " ← " + payload + ")");
        } catch (Exception e) {
            logger.warn("HA: command error (" + suffix + " ← " + payload + "): " + e.getMessage());
        }
    }

    private static int seatLevel(String payload) {
        switch (payload.trim().toUpperCase()) {
            case "LOW":    return 1;
            case "MEDIUM": return 2;
            case "HIGH":   return 3;
            default:       return 0;
        }
    }

    private void openTrunkSafely() throws Exception {
        BydCloudClient cloudClient = getCloudClient();
        cloudClient.unlock(getVin());
        Thread.sleep(2000);
        BydDataCollector.getInstance().openTailgate();
    }

    private BydCloudClient getCloudClient() throws Exception {
        BydCloudConfig cloudConfig = BydCloudConfig.fromUnifiedConfig();
        if (!cloudConfig.isConfigured()) throw new Exception("BYD Cloud not configured");
        BydCloudClient cloudClient = com.overdrive.app.byd.cloud.BydCloudDataProvider
                .getInstance().getSharedClient();
        if (cloudClient == null) throw new Exception("BYD Cloud client not initialized");
        if (!cloudConfig.vin.isEmpty()) cloudClient.verifyControlPassword(cloudConfig.vin);
        return cloudClient;
    }

    private static String getVin() throws Exception {
        BydCloudConfig cloudConfig = BydCloudConfig.fromUnifiedConfig();
        if (cloudConfig.vin == null || cloudConfig.vin.isEmpty()) throw new Exception("VIN not configured");
        return cloudConfig.vin;
    }

    // ==================== HA CONFIG BUILDERS ====================

    private JSONObject buildHaDevice(String uid) {
        JSONObject dev = new JSONObject();
        try {
            JSONArray ids = new JSONArray();
            ids.put("overdrive_" + uid);
            dev.put("identifiers", ids);
            dev.put("name", "Overdrive " + deviceId);
            dev.put("manufacturer", "Overdrive");
            dev.put("model", "BYD Vehicle");
        } catch (Exception ignored) {}
        return dev;
    }

    private JSONObject haSensor(JSONObject dev, String name, String stateTopic, String field,
                                 String availTopic, String deviceClass, String unit) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", name);
            cfg.put("state_topic", stateTopic);
            cfg.put("value_template", "{{ value_json." + field + " }}");
            cfg.put("availability_topic", availTopic);
            cfg.put("device", dev);
            if (deviceClass != null) cfg.put("device_class", deviceClass);
            if (unit != null) cfg.put("unit_of_measurement", unit);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haBinarySensor(JSONObject dev, String name, String stateTopic, String field,
                                       String availTopic, String deviceClass) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", name);
            cfg.put("state_topic", stateTopic);
            cfg.put("value_template", "{{ value_json." + field + " }}");
            cfg.put("payload_on", 1);
            cfg.put("payload_off", 0);
            cfg.put("availability_topic", availTopic);
            cfg.put("device", dev);
            if (deviceClass != null) cfg.put("device_class", deviceClass);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haWindowCover(JSONObject dev, String name, String stateTopic, String field,
                                      String availTopic, String cmdTopic) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", name);
            cfg.put("device_class", "window");
            cfg.put("state_topic", stateTopic);
            cfg.put("value_template",
                "{{ 'open' if value_json." + field + " | int > 0 else 'closed' }}");
            cfg.put("position_topic", stateTopic);
            cfg.put("position_template", "{{ value_json." + field + " }}");
            cfg.put("set_position_topic", cmdTopic);
            cfg.put("command_topic", cmdTopic);
            cfg.put("payload_open", "OPEN");
            cfg.put("payload_close", "CLOSE");
            cfg.put("payload_stop", "STOP");
            cfg.put("position_open", 100);
            cfg.put("position_closed", 0);
            cfg.put("availability_topic", availTopic);
            cfg.put("device", dev);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haTrunkCover(JSONObject dev, String stateTopic, String availTopic, String cmdTopic) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", "Trunk");
            cfg.put("device_class", "door");
            cfg.put("state_topic", stateTopic);
            cfg.put("value_template", "{{ 'open' if value_json.trunk_open else 'closed' }}");
            cfg.put("command_topic", cmdTopic);
            cfg.put("payload_open", "OPEN");
            cfg.put("payload_close", "CLOSE");
            cfg.put("payload_stop", "STOP");
            cfg.put("availability_topic", availTopic);
            cfg.put("device", dev);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haAcSwitch(JSONObject dev, String stateTopic, String availTopic, String cmdTopic) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", "Air Conditioning");
            cfg.put("state_topic", stateTopic);
            cfg.put("value_template", "{{ 'ON' if value_json.ac_on else 'OFF' }}");
            cfg.put("command_topic", cmdTopic);
            cfg.put("payload_on", "ON");
            cfg.put("payload_off", "OFF");
            cfg.put("availability_topic", availTopic);
            cfg.put("device", dev);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haOptimisticSwitch(JSONObject dev, String name, String cmdTopic) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", name);
            cfg.put("command_topic", cmdTopic);
            cfg.put("payload_on", "ON");
            cfg.put("payload_off", "OFF");
            cfg.put("optimistic", true);
            cfg.put("device", dev);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haNumber(JSONObject dev, String name, String stateTopic, String field,
                                 String availTopic, String cmdTopic, int min, int max, int step,
                                 boolean optimistic) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", name);
            cfg.put("command_topic", cmdTopic);
            cfg.put("min", min);
            cfg.put("max", max);
            cfg.put("step", step);
            cfg.put("device", dev);
            if (stateTopic != null && field != null) {
                cfg.put("state_topic", stateTopic);
                cfg.put("value_template", "{{ value_json." + field + " }}");
            }
            if (availTopic != null) cfg.put("availability_topic", availTopic);
            if (optimistic) cfg.put("optimistic", true);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haSelect(JSONObject dev, String name, String cmdTopic, String[] options) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", name);
            cfg.put("command_topic", cmdTopic);
            JSONArray opts = new JSONArray();
            for (String opt : options) opts.put(opt);
            cfg.put("options", opts);
            cfg.put("optimistic", true);
            cfg.put("device", dev);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haLock(JSONObject dev, String cmdTopic) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", "Door Lock");
            cfg.put("command_topic", cmdTopic);
            cfg.put("payload_lock", "LOCK");
            cfg.put("payload_unlock", "UNLOCK");
            cfg.put("optimistic", true);
            cfg.put("device", dev);
        } catch (Exception ignored) {}
        return cfg;
    }

    private JSONObject haButton(JSONObject dev, String name, String cmdTopic) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("name", name);
            cfg.put("command_topic", cmdTopic);
            cfg.put("payload_press", "PRESS");
            cfg.put("device", dev);
        } catch (Exception ignored) {}
        return cfg;
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
