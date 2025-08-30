/*
Hubitat App For Honeywell Thermostats (Resideo/LCC and TCC)

Copyright 2024 - Michael Bach
Rewritten and enhanced from original by Taylor Brown (2020)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Major Features in this Rewrite:
- Unified support for both modern Resideo (LCC) API via OAuth and legacy Total Comfort Connect (TCC) API via Username/Password.
- Abstracted API layer to handle differences between LCC and TCC gracefully.
- Integrated "Smart Control" mode to manage thermostat setpoint based on average temperature of occupied remote sensors.
- Robust error handling and intelligent API rate-limit management.
*/

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static String RESIDEO_API_URL = "https://api.honeywell.com"
@Field static String TCC_API_URL = "https://mytotalconnectcomfort.com/portal"
@Field static String OAUTH_REDIRECT_URL = "https://cloud.hubitat.com/oauth/stateredirect"

definition(
    name: "Honeywell Unofficial Thermostat",
    namespace: "mibach-crypto",
    author: "Michael Bach (based on work by Taylor Brown)",
    description: "A unified app for Resideo (LCC) and Total Comfort Connect (TCC) Honeywell thermostats.",
    importUrl: "https://raw.githubusercontent.com/mibach-crypto/hubitat-honeywell-Maintenance/main/honeywellhomeapp.groovy",
    category: "HVAC",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png"
)

preferences {
    page(name: "mainPage")
    page(name: "addAccountPage")
    page(name: "resideoLoginPage")
    page(name: "tccLoginPage")
    page(name: "smartControlPage")
    page(name: "debugPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Honeywell Thermostats", install: true, uninstall: true) {
        if (app.getInstallationState() != 'COMPLETE') {
            section { paragraph "Please hit 'Done' to complete the installation of the '${app.label}' app." }
            return
        }

        section("Accounts") {
            href(name: "addAccountHref", title: "Add a Honeywell Account", page: "addAccountPage", description: "Click here to add a Resideo or TCC account.")
            
            atomicState.accounts.each { accountId, account ->
                paragraph "${account.type} Account (${account.username ?: 'OAuth'})"
            }
        }

        section("Discovered Devices") {
            input 'discoverDevices', 'button', title: 'Discover Devices', submitOnChange: true
            listDiscoveredDevices()
        }

        section("Configuration") {
             input name: "refreshInterval", type: "enum", title: "Device Refresh Interval", options: [0:"Off", 1:"1 minute", 2:"2 minutes", 5:"5 minutes", 10:"10 minutes", 15:"15 minutes", 30:"30 minutes"], required: true, defaultValue: 10, submitOnChange: true
        }

        section("Smart Control") {
            href(name: "smartControlHref", title: "Configure Smart Control", page: "smartControlPage", description: "Control a thermostat based on remote sensor readings.")
        }
        
        section("Logging & Debugging") {
            input name: "logLevel", type: "enum", title: "Log Level", options: ["Trace", "Debug", "Info", "Warn", "Error"], defaultValue: "Info", submitOnChange: true
            href(name: "debugHref", title: "Debug Tools", page: "debugPage", description: "Advanced tools for troubleshooting.")
        }
    }
}

def addAccountPage() {
    dynamicPage(name: "addAccountPage", title: "Add Honeywell Account") {
        section {
            paragraph "Select the type of Honeywell account you would like to add. Use 'Resideo' for modern thermostats (like T-Series, Lyric) that use the Honeywell Home app. Use 'Total Comfort Connect' for older models."
            href(name: "addResideo", title: "Add Resideo Account (OAuth)", page: "resideoLoginPage", description: "For modern thermostats.")
            href(name: "addTcc", title: "Add Total Comfort Connect Account", page: "tccLoginPage", description: "For legacy thermostats.")
        }
    }
}

def resideoLoginPage() {
    dynamicPage(name: "resideoLoginPage", title: "Resideo (LCC) Account") {
        section {
            paragraph "To connect to the modern Resideo API, you need a Consumer Key and Secret from the Honeywell Developer Portal."
            paragraph """
                1) Go to https://developer.honeywellhome.com/ and create a free account.
                2) Navigate to 'My Apps' and click 'Create New App'.
                3) Give it a name (e.g., 'Hubitat').
                4) For the 'Callback URL', enter: <b>${OAUTH_REDIRECT_URL}</b>
                5) After creation, copy the 'Consumer Key' and 'Consumer Secret' into the fields below.
            """
            input name: "resideoConsumerKey", type: "text", title: "Consumer Key", required: true, submitOnChange: true
            input name: "resideoConsumerSecret", type: "password", title: "Consumer Secret", required: true, submitOnChange: true
        }
        if (resideoConsumerKey && resideoConsumerSecret) {
            section {
                href(name: "connectToResideo", title: "Connect to Honeywell/Resideo", page: "mainPage", description: "Click to authorize with Honeywell.")
            }
        }
    }
}

def tccLoginPage() {
    dynamicPage(name: "tccLoginPage", title: "Total Comfort Connect (TCC) Account") {
        section {
            paragraph "Enter your login credentials for the My Total Connect Comfort website."
            input name: "tccUsername", type: "text", title: "Username (Email)", required: true
            input name: "tccPassword", type: "password", title: "Password", required: true
        }
        section {
            input 'loginToTcc', 'button', title: 'Login and Add Account', submitOnChange: true
        }
    }
}

def smartControlPage() {
    dynamicPage(name: "smartControlPage", title: "Smart Control Configuration") {
        section {
            paragraph "This feature adjusts a primary thermostat's setpoint based on the average temperature of selected occupied rooms, similar to Flair Smart Vents."
            input name: "smartControlEnabled", type: "bool", title: "Enable Smart Control", defaultValue: false, submitOnChange: true
        }
        if (smartControlEnabled) {
            section("Configuration") {
                input name: "primaryThermostat", type: "capability.thermostat", title: "Primary Thermostat to Control", required: true, multiple: false
                input name: "tempSensors", type: "capability.temperatureMeasurement", title: "Temperature Sensors to Average", required: true, multiple: true
                input name: "motionSensors", type: "capability.motionSensor", title: "Occupancy Sensors", required: false, multiple: true, description: "If provided, only sensors in occupied rooms will be averaged."
                input name: "desiredSetpoint", type: "decimal", title: "Desired Room Setpoint", required: true
                input name: "controlOffset", type: "decimal", title: "Control Offset (°)", description: "How much to overcool/overheat to reach the setpoint. Default: 1.0", defaultValue: 1.0
            }
        }
    }
}

def debugPage() {
    dynamicPage(name: "debugPage", title: "Debug Tools") {
        section("Actions") {
            input 'forceRefreshAll', 'button', title: 'Force Refresh All Devices', submitOnChange: true
            input 'forceResideoTokenRefresh', 'button', title: 'Force Resideo Token Refresh', submitOnChange: true
            input 'deleteAllDevices', 'button', title: 'Delete All Child Devices', submitOnChange: true
        }
        section("State Variables") {
            paragraph "${atomicState}"
        }
    }
}

// --------------------
// Installation & Configuration
// --------------------
def installed() {
    log("Installing...", "info")
    initialize()
}

def updated() {
    log("Settings updated.", "info")
    initialize()
}

def uninstalled() {
    log("Uninstalling...", "info")
    deleteAllChildDevices()
}

def initialize() {
    unschedule()
    if (!atomicState.accounts) { atomicState.accounts = [:] }
    
    // Schedule periodic refresh
    if (refreshInterval.toInteger() > 0) {
        log("Scheduling refresh every ${refreshInterval} minutes.", "info")
        schedule("0 0/${refreshInterval} * * * ?", refreshAllDevices)
    }

    // Schedule Smart Control logic
    if (smartControlEnabled) {
        log("Scheduling Smart Control updates.", "info")
        subscribe(tempSensors, "temperature", smartControlHandler)
        if (motionSensors) {
            subscribe(motionSensors, "motion", smartControlHandler)
        }
        runIn(5, updateSmartSetpoint) // Initial run
    }
    
    // Refresh tokens
    atomicState.accounts.each { accountId, account ->
        if (account.type == "Resideo") {
            scheduleResideoTokenRefresh(accountId)
        }
    }
}

// --------------------
// Event Handlers
// --------------------
void appButtonHandler(btn) {
    switch (btn) {
        case 'discoverDevices': discoverDevices(); break
        case 'loginToTcc': loginToTcc(); break
        case 'forceRefreshAll': refreshAllDevices(); break
        case 'forceResideoTokenRefresh': 
            def resideoAccount = atomicState.accounts.find { it.value.type == "Resideo" }
            if (resideoAccount) refreshResideoToken(resideoAccount.key)
            break
        case 'deleteAllDevices': deleteAllChildDevices(); break
    }
}

void smartControlHandler(evt) {
    log("Smart Control trigger from ${evt.displayName}. Value: ${evt.value}", "debug")
    updateSmartSetpoint()
}

// --------------------
// OAuth & Login (Resideo)
// --------------------
def connectToResideo() {
    def accountId = "resideo_${resideoConsumerKey.take(8)}"
    if (!atomicState.accounts[accountId]) {
        atomicState.accounts[accountId] = [
            type: "Resideo",
            consumerKey: resideoConsumerKey,
            consumerSecret: resideoConsumerSecret
        ]
    }
    
    def authState = java.net.URLEncoder.encode("${getHubUID()}/apps/${app.id}/oauthCallback?accountId=${accountId}", "UTF-8")
    def escapedRedirectURL = java.net.URLEncoder.encode(OAUTH_REDIRECT_URL, "UTF-8")
    def authQueryString = "response_type=code&redirect_uri=${escapedRedirectURL}&client_id=${resideoConsumerKey}&state=${authState}"
    def authUrl = "${RESIDEO_API_URL}/oauth2/authorize?${authQueryString}"
    
    redirect(location: authUrl)
}

void oauthCallback(response) {
    def accountId = params.accountId
    def account = atomicState.accounts[accountId]
    if (!account) {
        log("OAuth callback received for unknown account ID: ${accountId}", "error")
        return
    }

    log("OAuth callback received, exchanging code for token.", "debug")
    def authCode = params.code
    def authorization = ("${account.consumerKey}:${account.consumerSecret}").bytes.encodeBase64().toString()

    def params = [
        uri: "${RESIDEO_API_URL}/oauth2/token",
        headers: [ Authorization: "Basic ${authorization}", Accept: "application/json" ],
        body: [ grant_type: "authorization_code", code: authCode, redirect_uri: OAUTH_REDIRECT_URL ]
    ]

    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                log("Successfully obtained Resideo tokens.", "info")
                account.access_token = resp.data.access_token
                account.refresh_token = resp.data.refresh_token
                account.expires_in = resp.data.expires_in
                atomicState.accounts[accountId] = account // Save updated account info
                scheduleResideoTokenRefresh(accountId)
            } else {
                log("Failed to get Resideo tokens. Status: ${resp.status}, Data: ${resp.data}", "error")
            }
        }
    } catch (e) {
        log("Error exchanging auth code for token: ${e.message}", "error")
    }
}

def scheduleResideoTokenRefresh(accountId) {
    def account = atomicState.accounts[accountId]
    if (account?.expires_in) {
        def refreshTime = account.expires_in.toInteger() - 300 // Refresh 5 minutes before expiry
        log("Scheduling Resideo token refresh for account ${accountId} in ${refreshTime} seconds.", "info")
        runIn(refreshTime, "refreshResideoToken", [data: [accountId: accountId]])
    }
}

void refreshResideoToken(String accountId) {
    def account = atomicState.accounts[accountId]
    if (!account || !account.refresh_token) {
        log("Cannot refresh Resideo token, no refresh token found for account ${accountId}.", "warn")
        return
    }

    log("Refreshing Resideo access token for account ${accountId}.", "info")
    def authorization = ("${account.consumerKey}:${account.consumerSecret}").bytes.encodeBase64().toString()
    def params = [
        uri: "${RESIDEO_API_URL}/oauth2/token",
        headers: [ Authorization: "Basic ${authorization}", Accept: "application/json" ],
        body: [ grant_type: "refresh_token", refresh_token: account.refresh_token ]
    ]
    
    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                log("Successfully refreshed Resideo tokens.", "info")
                account.access_token = resp.data.access_token
                account.refresh_token = resp.data.refresh_token
                account.expires_in = resp.data.expires_in
                atomicState.accounts[accountId] = account
                scheduleResideoTokenRefresh(accountId)
            } else {
                log("Failed to refresh Resideo token. Status: ${resp.status}, Data: ${resp.data}", "error")
            }
        }
    } catch (e) {
        log("Error refreshing Resideo token: ${e.message}", "error")
    }
}


// --------------------
// Login (TCC)
// --------------------
void loginToTcc() {
    if (!tccUsername || !tccPassword) {
        log("TCC Username or Password not provided.", "warn")
        return
    }

    def accountId = "tcc_${tccUsername}"
    log("Attempting to log in to TCC for user: ${tccUsername}", "info")
    
    def headers = [
        'Content-Type': 'application/x-www-form-urlencoded',
        'Accept': 'application/json'
    ]
    def body = "UserName=${tccUsername}&Password=${tccPassword}&RememberMe=true"
    def params = [
        uri: TCC_API_URL,
        headers: headers,
        body: body
    ]

    try {
        httpPost(params) { resp ->
            if (resp.status == 200 && resp.data?.success == true) {
                def cookie = resp.getHeaders('Set-Cookie').find { it.value.contains(".ASPXAUTH") }
                if (cookie) {
                    log("TCC login successful.", "info")
                    atomicState.accounts[accountId] = [
                        type: "TCC",
                        username: tccUsername,
                        password: tccPassword, // Note: Storing password is not ideal, but necessary for TCC
                        cookie: cookie.value,
                        userId: resp.data.userId
                    ]
                    page.redirect(action: "mainPage")
                } else {
                     log("TCC Login failed: ASPXAUTH cookie not found in response.", "error")
                }
            } else {
                log("TCC Login failed. Status: ${resp.status}, Response: ${resp.data}", "error")
            }
        }
    } catch (e) {
        log("Exception during TCC login: ${e.message}", "error")
    }
}


// --------------------
// Device Discovery & Management
// --------------------
def discoverDevices() {
    log("Starting device discovery...", "info")
    atomicState.accounts.each { accountId, account ->
        if (account.type == "Resideo" && account.access_token) {
            discoverResideoDevices(accountId)
        }
        if (account.type == "TCC" && account.cookie) {
            discoverTccDevices(accountId)
        }
    }
}

def discoverResideoDevices(String accountId) {
    def account = atomicState.accounts[accountId]
    log("Discovering Resideo devices...", "info")
    
    def params = [
        uri: "${RESIDEO_API_URL}/v2/locations?apikey=${account.consumerKey}",
        headers: [ Authorization: "Bearer ${account.access_token}" ]
    ]

    apiGet(params, { resp ->
        resp.data.each { location ->
            location.devices.each { dev ->
                if (dev.deviceClass == "Thermostat") {
                    def dni = "RES|${location.locationID}|${dev.deviceID}"
                    def existingDevice = getChildDevice(dni)
                    if (!existingDevice) {
                        log("Found new Resideo device: ${dev.userDefinedDeviceName} (${dev.deviceID})", "info")
                        def child = addChildDevice("mibach-crypto", "Honeywell Unified Thermostat", dni, [
                            name: "Honeywell ${dev.userDefinedDeviceName}",
                            label: dev.userDefinedDeviceName
                        ])
                        child.updateDataValue("apiSource", "Resideo")
                        child.updateDataValue("accountId", accountId)
                        child.updateDataValue("locationId", location.locationID.toString())
                    }
                }
            }
        }
    })
}

def discoverTccDevices(String accountId) {
    def account = atomicState.accounts[accountId]
    log("Discovering TCC devices...", "info")
    
    def params = [
        uri: "${TCC_API_URL}/GetLocations?userId=${account.userId}&allData=True",
        headers: [ Cookie: account.cookie ]
    ]

    apiGet(params, { resp ->
        resp.data.locations.each { location ->
            location.thermostats.each { dev ->
                def dni = "TCC|${location.locationID}|${dev.deviceID}"
                def existingDevice = getChildDevice(dni)
                if (!existingDevice) {
                    log("Found new TCC device: ${dev.userDefinedDeviceName} (${dev.deviceID})", "info")
                    def child = addChildDevice("mibach-crypto", "Honeywell Unified Thermostat", dni, [
                        name: "Honeywell ${dev.userDefinedDeviceName}",
                        label: dev.userDefinedDeviceName
                    ])
                    child.updateDataValue("apiSource", "TCC")
                    child.updateDataValue("accountId", accountId)
                    child.updateDataValue("locationId", location.locationID.toString())
                }
            }
        }
    })
}

def deleteAllChildDevices() {
    log("Deleting all child devices.", "warn")
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def listDiscoveredDevices() {
    def children = getChildDevices()
    if (children) {
        def links = "<ul>" + children.collect { "<li><a href='/device/edit/${it.id}'>${it.label} (${it.getDataValue("apiSource")})</a></li>" }.join("") + "</ul>"
        section {
            paragraph "The following devices have been discovered:"
            paragraph links
        }
    }
}


// --------------------
// Device Refresh
// --------------------
void refreshAllDevices() {
    log("Refreshing all devices...", "debug")
    getChildDevices().each { dev ->
        pauseExecution(1000) // Basic throttling to prevent hammering APIs
        refreshDevice(dev)
    }
}

void refreshDevice(device) {
    def apiSource = device.getDataValue("apiSource")
    if (apiSource == "Resideo") {
        refreshResideoThermostat(device)
    } else if (apiSource == "TCC") {
        refreshTccThermostat(device)
    }
}

def refreshResideoThermostat(device, retry = false) {
    def dniParts = device.deviceNetworkId.split('\\|')
    def locationId = dniParts[1]
    def deviceId = dniParts[2]
    def account = atomicState.accounts[device.getDataValue("accountId")]

    log("Refreshing Resideo device: ${device.label}", "debug")
    
    def params = [
        uri: "${RESIDEO_API_URL}/v2/devices/thermostats/${deviceId}?apikey=${account.consumerKey}&locationId=${locationId}",
        headers: [ Authorization: "Bearer ${account.access_token}" ]
    ]
    
    apiGet(params, { resp ->
        // Parse Resideo data and send events to driver
        def data = resp.data
        device.parse([
            temperature: data.indoorTemperature,
            humidity: data.indoorHumidity,
            heatingSetpoint: data.changeableValues.heatSetpoint,
            coolingSetpoint: data.changeableValues.coolSetpoint,
            thermostatMode: data.changeableValues.mode.toLowerCase(),
            thermostatFanMode: data.settings?.fan?.changeableValues?.mode?.toLowerCase() ?: "auto",
            thermostatOperatingState: parseOperatingState(data.operationStatus.mode),
            supportedThermostatModes: JsonOutput.toJson(data.allowedModes.collect{ it.toLowerCase() }),
            supportedThermostatFanModes: JsonOutput.toJson(data.settings?.fan?.allowedModes?.collect{ it.toLowerCase() } ?: ["auto", "on"]),
            units: data.units == "Fahrenheit" ? "F" : "C"
        ])

    }, { errorResp, errorData ->
        if (errorResp.status == 401 && !retry) {
            log("Resideo token expired for ${device.label}. Refreshing and retrying.", "warn")
            refreshResideoToken(device.getDataValue("accountId"))
            runIn(5, "refreshResideoThermostat", [data: [device: device, retry: true]])
        } else {
            log("Failed to refresh Resideo device ${device.label}. Status: ${errorResp.status}", "error")
        }
    })
}

def refreshTccThermostat(device, retry = false) {
    def dniParts = device.deviceNetworkId.split('\\|')
    def locationId = dniParts[1]
    def deviceId = dniParts[2]
    def account = atomicState.accounts[device.getDataValue("accountId")]
    
    log("Refreshing TCC device: ${device.label}", "debug")

    def params = [
        uri: "${TCC_API_URL}/GetLocations?userId=${account.userId}&allData=True",
        headers: [ Cookie: account.cookie ]
    ]
    
    apiGet(params, { resp ->
        def location = resp.data.locations.find { it.locationID == locationId.toInteger() }
        def thermo = location?.thermostats?.find { it.deviceID == deviceId.toInteger() }
        if (thermo) {
            device.parse([
                temperature: thermo.indoorTemperature,
                humidity: thermo.indoorHumidity,
                heatingSetpoint: thermo.changeableValues.heatSetpoint,
                coolingSetpoint: thermo.changeableValues.coolSetpoint,
                thermostatMode: parseTccMode(thermo.changeableValues.mode),
                thermostatFanMode: parseTccFanMode(thermo.fan.changeableValues.mode),
                thermostatOperatingState: parseOperatingState(thermo.operationStatus.mode),
                supportedThermostatModes: JsonOutput.toJson(["off", "heat", "cool", "auto"]),
                supportedThermostatFanModes: JsonOutput.toJson(["auto", "on", "circulate"]),
                units: thermo.units == "Fahrenheit" ? "F" : "C"
            ])
        } else {
            log("TCC device ${device.label} not found in refresh data.", "warn")
        }
    }, { errorResp, errorData ->
        // TCC auth is cookie based, might need re-login
        log("Failed to refresh TCC device ${device.label}. Status: ${errorResp.status}. Attempting re-login.", "warn")
        loginToTcc() // Re-login to get a fresh cookie
        runIn(10, "refreshTccThermostat", [data: [device: device, retry: true]])
    })
}


// --------------------
// Device Control
// --------------------
void setThermostat(device, Map settings) {
    def apiSource = device.getDataValue("apiSource")
    if (apiSource == "Resideo") {
        setResideoThermostat(device, settings)
    } else if (apiSource == "TCC") {
        setTccThermostat(device, settings)
    }
}

def setResideoThermostat(device, Map settings, retry = false) {
    def dniParts = device.deviceNetworkId.split('\\|')
    def locationId = dniParts[1]
    def deviceId = dniParts[2]
    def account = atomicState.accounts[device.getDataValue("accountId")]

    log("Setting Resideo device ${device.label} with: ${settings}", "debug")

    // Construct body
    def body = [:]
    if (settings.mode) body.mode = settings.mode.capitalize()
    if (settings.heatSetpoint) body.heatSetpoint = settings.heatSetpoint
    if (settings.coolSetpoint) body.coolSetpoint = settings.coolSetpoint

    def params = [
        uri: "${RESIDEO_API_URL}/v2/devices/thermostats/${deviceId}?apikey=${account.consumerKey}&locationId=${locationId}",
        headers: [ Authorization: "Bearer ${account.access_token}", 'Content-Type': 'application/json' ],
        body: JsonOutput.toJson(body)
    ]
    
    apiPost(params, { resp ->
        log("Successfully set Resideo device ${device.label}.", "info")
        runIn(2, "refreshDevice", [data: [device: device]]) // Refresh after a short delay
    }, { errorResp, errorData ->
        if (errorResp.status == 401 && !retry) {
            log("Resideo token expired on set command. Refreshing and retrying.", "warn")
            refreshResideoToken(device.getDataValue("accountId"))
            runIn(5, "setResideoThermostat", [data: [device: device, settings: settings, retry: true]])
        } else {
            log("Failed to set Resideo device ${device.label}. Status: ${errorResp.status}, Body: ${errorData}", "error")
        }
    })
}

def setTccThermostat(device, Map settings, retry = false) {
    def dniParts = device.deviceNetworkId.split('\\|')
    def deviceId = dniParts[2]
    def account = atomicState.accounts[device.getDataValue("accountId")]
    
    log("Setting TCC device ${device.label} with: ${settings}", "debug")

    def body = [ DeviceId: deviceId.toInteger() ]
    if (settings.mode) body.SystemSwitch = getTccSystemSwitch(settings.mode)
    if (settings.heatSetpoint) body.HeatSetpoint = settings.heatSetpoint
    if (settings.coolSetpoint) body.CoolSetpoint = settings.coolSetpoint
    // TCC requires status for holds
    if (settings.mode || settings.heatSetpoint || settings.coolSetpoint) {
        body.StatusHeat = 1 // 1 for Hold, 0 for FollowSchedule
        body.StatusCool = 1
    }

    def params = [
        uri: "${TCC_API_URL}/Device/SubmitControlScreenChanges",
        headers: [ Cookie: account.cookie, 'Content-Type': 'application/json' ],
        body: JsonOutput.toJson(body)
    ]

    apiPost(params, { resp ->
        log("Successfully set TCC device ${device.label}.", "info")
        runIn(5, "refreshDevice", [data: [device: device]])
    }, { errorResp, errorData ->
        log("Failed to set TCC device ${device.label}. Status: ${errorResp.status}. Re-logging in and retrying.", "warn")
        loginToTcc()
        if (!retry) {
            runIn(10, "setTccThermostat", [data: [device: device, settings: settings, retry: true]])
        }
    })
}


// --------------------
// Smart Control Logic
// --------------------
void updateSmartSetpoint() {
    if (!smartControlEnabled || !primaryThermostat || !tempSensors) {
        return
    }

    log("Updating Smart Setpoint...", "debug")
    def activeTemps = []
    
    if (motionSensors) {
        // Use only sensors in occupied rooms
        def occupied = motionSensors.any { it.currentValue("motion") == "active" }
        if (occupied) {
             motionSensors.each { motion ->
                if (motion.currentValue("motion") == "active") {
                    // This is a simplification; assumes a 1:1 mapping of motion to temp sensor
                    // A better implementation would require linking sensors in the UI
                    def linkedTempSensor = tempSensors.find{ it.label.contains(motion.label.split(" ")[0]) } // Heuristic matching
                    if(linkedTempSensor) activeTemps << linkedTempSensor.currentValue("temperature")
                }
            }
        } else {
            // No motion, don't adjust
            log("Smart Control: No motion detected, not adjusting setpoint.", "info")
            return
        }
    } else {
        // No motion sensors, use all temperature sensors
        activeTemps = tempSensors.collect { it.currentValue("temperature") }
    }
    
    if (activeTemps.size() == 0) {
        log("Smart Control: No active temperature sensors found.", "info")
        return
    }

    def avgTemp = activeTemps.sum() / activeTemps.size()
    def currentMode = primaryThermostat.currentValue("thermostatMode")
    log("Smart Control: Avg temp of ${activeTemps.size()} rooms is ${avgTemp}. Desired: ${desiredSetpoint}. Mode: ${currentMode}", "debug")

    def newSettings = [:]
    if (currentMode == "cool" && avgTemp > desiredSetpoint) {
        newSettings.coolSetpoint = (desiredSetpoint - controlOffset).round(1)
        log("Smart Control: Rooms are warm. Setting thermostat cool setpoint to ${newSettings.coolSetpoint}", "info")
    } else if (currentMode == "heat" && avgTemp < desiredSetpoint) {
        newSettings.heatSetpoint = (desiredSetpoint + controlOffset).round(1)
        log("Smart Control: Rooms are cold. Setting thermostat heat setpoint to ${newSettings.heatSetpoint}", "info")
    } else {
        log("Smart Control: Rooms are comfortable. No change needed.", "info")
        // Optional: return thermostat to a neutral setpoint
        return
    }

    // Send command to the primary thermostat driver
    if (newSettings) {
        primaryThermostat.set(newSettings)
    }
}


// --------------------
// Utility Functions
// --------------------
def apiGet(Map params, Closure success, Closure failure = {}) {
    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                success(resp)
            } else {
                failure(resp, resp.data)
            }
        }
    } catch (e) {
        log("Exception in apiGet: ${e.message}", "error")
        failure(null, e.message)
    }
}

def apiPost(Map params, Closure success, Closure failure = {}) {
    try {
        httpPost(params) { resp ->
            if (resp.status == 200) {
                success(resp)
            } else {
                failure(resp, resp.data)
            }
        }
    } catch (e) {
        log("Exception in apiPost: ${e.message}", "error")
        failure(null, e.message)
    }
}

def log(message, level = "debug") {
    def minLevel = determineLogLevel(logLevel ?: "Info")
    def currentLevel = determineLogLevel(level)
    if (currentLevel >= minLevel) {
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

String parseOperatingState(state) {
    switch(state) {
        case "EquipmentOff": return "idle"
        case "Heat": return "heating"
        case "Cool": return "cooling"
        case "Fan": return "fan only"
        default: return "unknown"
    }
}

String parseTccMode(mode) {
    switch(mode) {
        case 1: return "heat"
        case 2: return "cool"
        case 3: return "off"
        case 4: return "auto"
        default: return "off"
    }
}

int getTccSystemSwitch(mode) {
    switch(mode) {
        case "heat": return 1
        case "cool": return 2
        case "off": return 3
        case "auto": return 4
        default: return 3
    }
}

String parseTccFanMode(mode) {
    switch(mode) {
        case 0: return "auto"
        case 1: return "on"
        case 2: return "circulate"
        default: return "auto"
    }
}
