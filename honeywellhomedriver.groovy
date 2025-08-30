/*
Hubitat Driver For Honeywell Unified Thermostats (Resideo/LCC and TCC)

Copyright 2024 - Michael Bach
Based on original by Taylor Brown (2020)

This driver is designed to work with the "Honeywell Unofficial Thermostat" app.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/
import groovy.json.JsonSlurper

metadata {
    definition (
        name: "Honeywell Unified Thermostat", 
        namespace: "mibach-crypto", 
        author: "Michael Bach (based on work by Taylor Brown)",
        importUrl: "https://raw.githubusercontent.com/mibach-crypto/hubitat-honeywell-Maintenance/main/honeywellhomedriver.groovy"
    ) {
        capability "Thermostat"
        capability "Temperature Measurement"
        capability "Relative Humidity Measurement"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"

        attribute "units", "enum", ["F", "C"]
        attribute "lastUpdate", "string"
    }

    preferences {
        input ("logLevel", "enum", 
               title: "Log Level", 
               options: ["Trace", "Debug", "Info", "Warn", "Error"],
               defaultValue: "Info")
    }
}

void installed() {
    log("Device installed.", "info")
    refresh()
}

void updated() {
    log("Device preferences updated.", "info")
}

void parse(Map data) {
    log("Parsing data from parent app: ${data}", "trace")

    data.each { key, value ->
        if (value != null) {
            // Special handling for JSON strings
            if (key in ["supportedThermostatModes", "supportedThermostatFanModes"]) {
                try {
                    def list = new JsonSlurper().parseText(value)
                    sendEvent(name: key, value: list)
                } catch (e) {
                    log("Error parsing JSON for ${key}: ${value}", "error")
                }
            } else {
                def eventProps = [name: key, value: value]
                if (key == "temperature" || key == "heatingSetpoint" || key == "coolingSetpoint") {
                    eventProps.unit = device.currentValue("units")
                }
                if (key == "humidity") {
                    eventProps.unit = "%"
                }
                sendEvent(eventProps)
            }
        }
    }
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

void set(Map settings) {
    parent.setThermostat(device, settings)
}

void setCoolingSetpoint(temperature) {
    log("Setting cooling setpoint to ${temperature}", "debug")
    parent.setThermostat(device, [coolSetpoint: temperature])
}

void setHeatingSetpoint(temperature) {
    log("Setting heating setpoint to ${temperature}", "debug")
    parent.setThermostat(device, [heatSetpoint: temperature])
}

void setThermostatMode(mode) {
    log("Setting thermostat mode to ${mode}", "debug")
    parent.setThermostat(device, [mode: mode])
}

void setThermostatFanMode(fanMode) {
    log("Setting thermostat fan mode to ${fanMode}", "debug")
    // Note: The new parent app structure handles fan mode through the main 'setThermostat' call
    // If a dedicated fan API endpoint is needed, it would be added here.
    // For now, this is a placeholder or can be extended.
    log("Fan mode control is handled by the parent app; this command is a placeholder.", "info")
}

void off() { setThermostatMode("off") }
void heat() { setThermostatMode("heat") }
void cool() { setThermostatMode("cool") }
void auto() { setThermostatMode("auto") }
void emergencyHeat() { log("Emergency Heat not supported by this driver.", "warn") }

void fanAuto() { setThermostatFanMode("auto") }
void fanOn() { setThermostatFanMode("on") }
void fanCirculate() { setThermostatFanMode("circulate") }

void refresh() {
    log("Refresh command received.", "debug")
    parent.refreshDevice(device)
}

// --------------------
// Utility Functions
// --------------------
def log(message, level = "debug") {
    def minLevel = determineLogLevel(logLevel ?: "Info")
    def currentLevel = determineLogLevel(level)
    if (currentLevel >= minLevel) {
        message = "${device.displayName} - ${message}"
        switch (level.toLowerCase()) {
            case "trace": log.trace message; break
            case "debug": log.debug message; break
            case "info": log.info message; break
            case "warn": log.warn message; break
            case "error": log.error message; break
        }
    }
}

private determineLogLevel(level) {
    switch (level.toLowerCase()) {
        case "trace": return 0
        case "debug": return 1
        case "info": return 2
        case "warn": return 3
        case "error": return 4
        default: return 2
    }
}
