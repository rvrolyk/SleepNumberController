/**
 *  Sleep Number Controller Driver
 *
 *  Usage:
 *    Allows controlling Sleep Number Flexible bases including presence detection.
 *    This driver uses the mode and side to communicate back to the parent app where all controls happen.
 *
 *-------------------------------------------------------------------------------------------------------------------
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *-------------------------------------------------------------------------------------------------------------------
 *
 *  If modifying this project, please keep the above header intact and add your comments/credits below - Thank you!
 * 
 *  Thanks to Nathan Jacobson and Tim Parsons for their work on SmartThings apps that do this.  This isn't a copy
 *  of those but leverages prior work they"ve done for the API calls and bed management.
 *    https://github.com/natecj/SmartThings/blob/master/smartapps/natecj/sleepiq-manager.src/sleepiq-manager.groovy
 *    https://github.com/ClassicTim1/SleepNumberManager/blob/master/FlexBase/SmartApp.groovy
 */

import com.hubitat.app.DeviceWrapper
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.util.GregorianCalendar

@Field static final ArrayList<String> TYPES = ["presence", "head", "foot", "foot warmer"]

@Field static final ArrayList<String> SIDES = ["Right", "Left"]
@Field static final Map<String, Integer> HEAT_TEMPS = [Off: 0, Low: 31, Medium: 57, High: 72]
@Field static final Map<String, Integer> HEAT_TIMES = ["30m": 30, "1h": 60, "2h": 120, "3h": 180, "4h": 240, "5h": 300, "6h": 360]
@Field static final Map<String, String> ACTUATOR_TYPES = [head: "H", foot: "F"]
@Field static final Map<String, Integer> PRESET_TIMES = ["Off": 0, "15m": 15, "30m": 30, "45m": 45, "1h": 60, "2h": 120, "3h": 180]
@Field static final Map<String, Integer> PRESET_NAMES = [Favorite: 1, Flat: 4, ZeroG: 5, Snore: 6, WatchTV: 3, Read: 2]
@Field static final ArrayList<String> UNDERBED_LIGHT_STATES = ["Auto", "On", "Off"]
@Field static final Map<String, Integer> UNDERBED_LIGHT_BRIGHTNESS = [Low: 1, Medium: 30, High: 100]
@Field static final Map<String, Integer> UNDERBED_LIGHT_TIMES = ["Forever": 0, "15m": 15, "30m": 30, "45m": 45, "1h": 60, "2h": 120, "3h": 180]
@Field static final ArrayList<String> OUTLET_STATES = ["On", "Off"]
@Field static final String DNI_SEPARATOR = "-"

metadata {
  definition(name: DRIVER_NAME,
             namespace: NAMESPACE,
             author: "Russ Vrolyk",
             importUrl: "https://raw.githubusercontent.com/rvrolyk/SleepNumberController/master/SleepNumberController_Driver.groovy"
  ) {
    capability "Actuator"
    capability "Switch"
    capability "SwitchLevel"
    capability "PresenceSensor"
    capability "Polling"

    // indicator for overall connectivity to Sleep Number API
    attribute "connection", "enum", ["online", "offline"]
    attribute "headPosition", "number"
    attribute "footPosition", "number"
    attribute "footWarmingTemp", "enum", HEAT_TEMPS.collect{ it.key }
    attribute "footWarmingTimer", "enum", HEAT_TIMES.collect{ it.key }
    attribute "sleepNumber", "number"
    // The user's sleep number favorite
    attribute "sleepNumberFavorite", "number"
    // The current preset position of the side
    attribute "positionPreset", "string"
    // The preset that the bed will change to once timer is done
    attribute "positionPresetTimer", "string"
    // The timer for the preset change
    attribute "positionTimer", "number"
    attribute "privacyMode", "enum", ["on", "off"]
    attribute "underbedLightTimer", "string" // String so we can use "Forever"
    attribute "underbedLightState", "enum", UNDERBED_LIGHT_STATES
    attribute "underbedLightBrightness", "enum", UNDERBED_LIGHT_BRIGHTNESS.collect{ it.key }
    attribute "outletState", "enum", OUTLET_STATES
    // Attributes for sleep IQ data
    attribute "sleepMessage", "string"
    attribute "sleepScore", "number"
    attribute "restfulAverage", "string"
    attribute "restlessAverage", "string"
    attribute "heartRateAverage", "number"
    attribute "HRVAverage", "number"
    attribute "breathRateAverage", "number"
    attribute "outOfBedTime", "string"
    attribute "inBedTime", "string"
    attribute "timeToSleep", "string"
    attribute "sessionStart", "date"
    attribute "sessionEnd", "date"
    attribute "sleepDataRefreshTime", "date"
    attribute "sleepIQSummary", "string"
    attribute "sessionSummary", "string"
    // Responsive Air state - optional based on preference since it requires another HTTP request
    // and most users probably don't care about it.
    attribute "responsiveAir", "enum", ["true", "false"]

    command "setRefreshInterval", [[name: "interval", type: "NUMBER", constraints: ["NUMBER"]]]
    command "arrived"
    command "departed"
    command "setSleepNumber", [[name: "sleep number", type: "NUMBER", constraints: ["NUMBER"]]]
    command "setBedPosition", [[name: "position", type: "NUMBER", constraints: ["NUMBER"]],
      [name: "actuator", type: "ENUM", constraints: ACTUATOR_TYPES.collect{ it.value }]]
    command "setFootWarmingState", [[name: "temp", type: "ENUM", constraints: HEAT_TEMPS.collect{ it.key }],
       [name: "timer", type: "ENUM", constraints: HEAT_TIMES.collect{ it.key }]]
    command "setBedPreset", [[name: "preset", type: "ENUM", constraints: PRESET_NAMES.collect{ it.key }]]
    command "setBedPresetTimer", [[name: "preset", type: "ENUM", constraints: PRESET_NAMES.collect{ it.key }],
        [name: "timer", type: "ENUM", constraints: PRESET_TIMES.collect{ it.key }]]
    command "stopBedPosition"
    command "enablePrivacyMode"
    command "disablePrivacyMode"
    command "getSleepData"
    command "setSleepNumberFavorite"
    command "setOutletState", [[name: "state", type: "ENUM", constraints: OUTLET_STATES]]
    command "setUnderbedLightState", [[name: "state", type: "ENUM", constraints: UNDERBED_LIGHT_STATES],
        [name: "timer", type: "ENUM", constraints: UNDERBED_LIGHT_TIMES.collect{ it.key }],
        [name: "brightness", type: "ENUM", constraints: UNDERBED_LIGHT_BRIGHTNESS.collect{ it.key }]]
    command "setResponsiveAirState", [[name: "state", type: "ENUM", constraints: ["true", "false"]]]
  }

  preferences {
    section("Settings:") {
      input name: "logEnable", type: "bool", title: "Enable Debug logging", defaultValue: false
      input name: "presetLevel", type: "enum", title: "Bed preset level for 'on'", options: PRESET_NAMES.collect{ it.key }, defaultValue: "Favorite"
      input name: "footWarmerLevel", type: "enum", title: "Foot warmer level for 'on'", options: HEAT_TEMPS.collect{ it.key }, defaultValue: "Medium"
      input name: "footWarmerTimer", type: "enum", title: "Foot warmer duration for 'on'", options: HEAT_TIMES.collect{ it.key }, defaultValue: "30m"
      input name: "underbedLightTimer", type: "enum", title: "Underbed light timer for 'on'", options: UNDERBED_LIGHT_TIMES.collect{ it.key }, defaultValue: "15m"
      input name: "enableSleepData", type: "bool", title: "Enable sleep data collection", defaultValue: false
      input name: "enableResponsiveAir", type: "bool", title: "Enable responsive air data", defaultValue: false
    }
  }
}

void installed() {
  debug "installed()"
  updated()
}

void logsOff() {
  logInfo "Debug logging disabled..."
  device.updateSetting "logEnable", [value: "false", type: "bool"]
}

void updated() {
  debug "updated()"
  if ((Boolean) settings.logEnable) {
    runIn(1800, logsOff)
  }
  if ((Boolean) settings.enableSleepData) {
    getSleepData()
  }
  // Poll right after updated / installed
  poll()
}

void uninstalled() {
  // Delete all the children when this is uninstalled.
  childDevices.each { deleteChildDevice((String) it.deviceNetworkId) }
}

void parse(String description) {
  debug "parse() - Description: ${description}"
}

// Required by Polling capability
void poll() {
  debug "poll()"
  sendToParent "refreshChildDevices"
}

// Required by Switch capability
void on() {
  sendEvent name: "switch", value: "on"
  if ((String) state.type == "foot warmer") {
    debug "on(): foot warmer"
    setFootWarmingState(footWarmerLevel, footWarmerTimer)
  } else {
    debug "on(): set preset ${settings.presetLevel}"
    setBedPreset(presetLevel)
  }
}

// Required by Switch capability
void off() {
  sendEvent name: "switch", value: "off"
  if ((String) state.type == "foot warmer") {
    debug "off(): foot warmer"
    setFootWarmingState("Off")
  } else {
    debug "off(): set Flat"
    setBedPreset("Flat")
  }
}

// setLevel required by SwitchLevel capability
// including one with duration (which we currently ignore).
void setLevel(Number val, Number duration) {
  setLevel(val)
}

void setLevel(Number val) {
  if ((String) state.type) {
    switch ((String) state.type) {
      case "presence":
        debug "setLevel(${val}): sleepNumber"
        setSleepNumber(val)
        break
      case "head":
        debug "setLevel(${val}): head position"
        setBedPosition(val)
        break
      case "foot":
        debug "setLevel(${val}): foot position"
        setBedPosition(val)
        break
      case "foot warmer":
        String level = null
        switch (val) {
          case 1:
            level = "Low"
            break
          case 2:
            level = "Medium"
            break
          case 3:
            level = "High"
            break
        }
        if (!level) {
          logError "Invalid level for warmer state.  Only 1, 2 or 3 is valid"
          return
        }
        debug "setLevel(${val}): warmer level to ${level}"
        setFootWarmingState(level)
        break
    }
  } else {
    debug "setLevel(${val}): sleepNumber"
    setSleepNumber(val)
    sendEvent name: "level", value: val
  }
}

// Required by PresenceSensor capability
Boolean isPresent() {
  return device.currentValue("presence") == "present"
}

void arrived() {
  debug "arrived()"
  if (!isPresent() && isPresenceOrParent()) {
    logInfo "${device.displayName} arrived"
    sendEvent name: "presence", value: "present"
  }
}

void departed() {
  debug "departed()"
  if (isPresent() && isPresenceOrParent()) {
    logInfo "${device.displayName} departed"
    sendEvent name: "presence", value: "not present"
    if ((Boolean) settings.enableSleepData) {
      getSleepData()
    }
  }
}

void setPresence(Boolean val) {
  debug "setPresence(${val})"
  if (val) {
    arrived()
  } else {
    departed()
  }
}

void setBedId(String val) {
  debug "setBedId(${va}l)"
  state.bedId = val
}

void setSide(String val) {
  debug "setSide(${val})"
  if (!SIDES.contains(val)) {
    logError "Invalid side ${val}, possible values are ${SIDES}"
  }
  state.side = val
}

void setRefreshInterval(Number val) {
  debug "setRefreshInterval(${val})"
  sendToParent "setRefreshInterval", val
}

void setSleepNumber(Number val) {
  debug "setSleepNumber(${val})"
  if (val > 0 && val <= 100) {
    sendToParent "setSleepNumber", val
  } else {
    logError "Invalid number, must be between 1 and 100"
  }
}

void setBedPosition(Number val, String actuator = null) {
  debug "setBedPosition(${val})"
  String type = actuator ?: ACTUATOR_TYPES.get(state.type)
  if (!type) {
    logError "Cannot determine actuator"
    return
  }
  if (val >= 0 && val <= 100) {
    sendToParent "setFoundationAdjustment", [actuator: type, position: val]
  } else {
    logError "Invalid position, must be between 0 and 100"
  }
}

void setFootWarmingState(String temp = "OFF", String timer = "30m") {
  debug "setWarmerState(${temp}, ${timer})"
  if (!HEAT_TIMES.get(timer)) {
    logError "Invalid warming time ${timer}"
    return
  }
  setFootWarmingState(temp, HEAT_TIMES.get(timer))
}

void setFootWarmingState(String temp = "OFF", Number duration) {
  debug "setWarmerState(${temp}, ${duration})"
  if (HEAT_TEMPS.get(temp) == null) {
    logError "Invalid warming temp ${temp}"
    return
  }
  if (duration == null) {
    logError "Invalid warming time ${duration}"
    return
  }
  if (!HEAT_TIMES.values().contains(duration.toInteger())) {
    logError "Invalid warming time ${duration}"
    return
  }
  sendToParent "setFootWarmingState", [temp: HEAT_TEMPS.get(temp), timer: duration.toInteger()]
}

void setBedPreset(String preset) {
  debug "setBedPreset(${preset})"
  if (preset == null || PRESET_NAMES.get(preset) == null) {
    logError "Invalid preset ${preset}"
    return
  }
  sendToParent "setFoundationPreset", PRESET_NAMES.get(preset)
}

void setBedPresetTimer(String preset, String timer) {
  debug "setBedPresetTimer(${preset}, ${timer})"
  if (preset == null || PRESET_NAMES.get(preset) == null) {
    logError "Invalid preset ${preset}"
    return
  }
  if (timer == null || PRESET_TIMES.get(timer) == null) {
    logError "Invalid preset timer ${timer}"
    return
  }
  sendToParent "setFoundationTimer", [preset: PRESET_NAMES.get(preset), timer: PRESET_TIMES.get(timer)]
}

void stopBedPosition() {
  debug "stopBedPostion()"
  sendToParent "stopFoundationMovement"
}

void enablePrivacyMode() {
  debug "enablePrivacyMode()"
  sendToParent "setPrivacyMode", true
}

void disablePrivacyMode() {
  debug "disablePrivacyMode()"
  sendToParent "setPrivacyMode", false
}

/**
 * Sets the SleepNumber to the preset favorite.
 */
void setSleepNumberFavorite() {
  debug "setSleepNumberFavorite()"
  sendToParent "setSleepNumberFavorite"
}

// TODO: Add updateSleepNumberFavorite?

void setOutletState(String state) {
  debug "setOutletState(${state})"
  if (state == null || !OUTLET_STATES.contains(state)) {
    logError "Invalid state ${state}"
    return
  }
  sendToParent "setOutletState", state
}

void setUnderbedLightState(String state, String timer = "Forever", String brightness = "High") {
  debug "setUnderbedLightState(${state}, ${timer}, ${brightness})"
  if (state == null || !UNDERBED_LIGHT_STATES.contains(state)) {
    logError "Invalid state ${state}"
    return
  }
  if (timer != null && UNDERBED_LIGHT_TIMES.get(timer) == null) {
    logError "Invalid timer ${timer}"
    return
  }
  if (brightness && !UNDERBED_LIGHT_BRIGHTNESS.get(brightness)) {
    logError "Invalid brightness ${brightness}"
    return
  }
  sendToParent "setUnderbedLightState", [state: state,
    timer: UNDERBED_LIGHT_TIMES.get(timer),
    brightness: UNDERBED_LIGHT_BRIGHTNESS.get(brightness)]
}

void setResponsiveAirState(String state) {
  debug "setResponsiveAirState($state)"
  sendToParent "setResponsiveAirState", Boolean.valueOf(state)
}

void getSleepData() {
  if (!isPresenceOrParent()) {
    logError "Sleep data only available on presence (main) device, this is ${state.type}"
    return
  }
  Map data = sendToParent "getSleepData"
  debug "sleep data ${data}"

  if (!data || data.sleepSessionCount == 0) {
    logInfo "No sleep sessions found, skipping update"
    return
  }

  // Set basic attributes
  // device.currentValue(name, true) doesn't seem to avoid the cache so stash the values
  // used in the summary tiles.
  sendEvent name: "sleepDataRefreshTime", value: new Date(now()).format("yyyy-MM-dd'T'HH:mm:ssXXX")
  sendEvent name: "sleepMessage", value: data.sleepData.message.find{it != ''}
  def sleepScore = data.sleepIQAvg
  sendEvent name: "sleepScore", value: sleepScore
  def restfulAvg = convertSecondsToTimeString(data.restfulAvg)
  sendEvent name: "restfulAverage", value: restfulAvg
  def restlessAvg = convertSecondsToTimeString(data.restlessAvg)
  sendEvent name: "restlessAverage", value: restlessAvg
  def heartRateAvg = data.heartRateAvg
  sendEvent name: "heartRateAverage", value: heartRateAvg
  def hrvAvg = data.hrvAvg
  sendEvent name: "HRVAverage", value: hrvAvg
  def breathRateAvg = data.respirationRateAvg
  sendEvent name: "breathRateAverage", value: breathRateAvg
  def outOfBedTime = convertSecondsToTimeString(data.outOfBedTotal)
  sendEvent name: "outOfBedTime", value: outOfBedTime
  def inBedTime = convertSecondsToTimeString(data.inBedTotal)
  sendEvent name: "inBedTime", value: inBedTime
  def timeToSleep = convertSecondsToTimeString(data.fallAsleepPeriod)
  sendEvent name: "timeToSleep", value: timeToSleep
  sendEvent name: "sessionStart", value: data.sleepData.sessions[0].startDate[0]
  sendEvent name: "sessionEnd", value: data.sleepData.sessions[data.sleepData.sessions.size() - 1].endDate[0]

  String table = '<table class="sleep-tiles %extraClasses" style="width:100%;font-size:12px;font-size:1.5vmax" id="%id">'
  // Set up tile attributes
  // Basic tile to represent what app shows when launched: last score, heart rate, hrv, breath rate
  String iqTile = table.replaceFirst('%id', "sleepiq-summary-${device.getLabel().toLowerCase().replaceAll(" ", "_")}")
      .replaceFirst('%extraClasses', "sleepiq-summary")
  iqTile += '<tr><th style="text-align: center; width: 50%">SleepIQ Score</th><th style="text-align: center">Breath Rate</th></tr>'
  iqTile += '<tr><td style="text-align: center">'
  iqTile += "${sleepScore}</td>"
  iqTile += '<td style="text-align: center">' + breathRateAvg + '</td></tr>'
  iqTile += '<tr><th style="text-align: center">Heart Rate</th><th style="text-align: center">HRV</th></tr>'
  iqTile += '<tr><td style="text-align: center">'
  iqTile += "${heartRateAvg}</td>"
  iqTile += '<td style="text-align: center">' + hrvAvg + '</td></tr>'
  iqTile += '</table>'
  sendEvent name: "sleepIQSummary", value: iqTile
  // Basic tile to aggregate session stats: time in bed, time to sleep, restful, restless, bed exits
  String summaryTile = table.replaceFirst('%id', "session-summary-${device.getLabel().toLowerCase().replaceAll(" ", "_")}")
      .replaceFirst('%extraClasses', "session-summary")
  summaryTile += "<tr><td colspan=2>In bed for ${inBedTime}</td></tr>"
  summaryTile += '<tr><th style="text-align: center; width: 50%">Time to fall asleep</th><th style="text-align: center">Restful</th></tr>'
  summaryTile += '<tr><td style="text-align: center">' + timeToSleep + '</td>'
  summaryTile += '<td style="text-align: center">' + restfulAvg + '</td></tr>'
  summaryTile += '<tr><th style="text-align: center">Restless</th><th style="text-align: center">Bed Exit</th></tr>'
  summaryTile += '<tr><td style="text-align: center">' + restlessAvg + '</td>'
  summaryTile += '<td style="text-align: center">' + outOfBedTime + '</td>'
  summaryTile += '</tr></table>'
  sendEvent name: "sessionSummary", value: summaryTile
}

static String convertSecondsToTimeString(int secondsToConvert) {
  new GregorianCalendar(0, 0, 0, 0, 0, secondsToConvert, 0).time.format("HH:mm:ss")
}

// Method used by parent app to set bed state
void setStatus(Map params) {
  debug "setStatus(${params})"
  if ((String) state.type) {
    setStatusOld(params)
    return
  }
  // No type means we are using parent/child devices.
  List<String> validAttributes = device.supportedAttributes.collect{ (String) it.name }
  params.each { param ->
    if (param.key in validAttributes) {
      def attributeValue = device."current${param.key.capitalize()}"
      def value = param.value
      // Translate some of the values into something more meaningful for comparison
      // but leave the other values alone
      if (param.key == "footWarmingTemp") {
        value = HEAT_TEMPS.find{ it.value == Integer.valueOf(value) }
        if (value == null) {
          logError "Invalid foot warming temp ${param.value}"
        } else {
          value = value.key
        }
      } else if (param.key == "underbedLightBrightness") {
        Map<Integer, String> brightnessValuesToNames = UNDERBED_LIGHT_BRIGHTNESS.collectEntries{
            e -> [(e.value): e.key]
        }
        value = brightnessValuesToNames.get(Integer.valueOf(value))
        if (value == null) {
          logWarn "Invalid underbedLightBrightness ${param.value}, using Low"
          value = "Low"
        }
      }

      debug "Setting ${param.key} to ${value}, it was ${attributeValue}"
      // Figure out what child device to send to based on the key.
      switch (param.key) {
        case "sleepNumber":
          // This is for this device so just send the event.
          sendEvent name: "level", value: value
          break
        case "positionPreset":
          if (value == "Flat") {
            sendEvent name: "switch", value: "off"
          } else if (value == presetLevel) {
            // On if the level is the desired preset.
            // Note this means it's off even when raised if it doesn't match a preset which
            // may not make sense given there is a level.  But since it can be "turned on"
            // when not at preset level, the behavior (if not the indicator) seems logical.
            sendEvent name: "switch", value: "on"
          }
          break
        case "headPosition":
          childDimmerLevel("head", value)
          break
        case "footPosition":
          childDimmerLevel("foot", value)
          break
        case "footWarmingTemp":
          def level = 0
          switch (value) {
              case "Off":
                level = 0
                break
              case "Low":
                level = 1
                break
              case "Medium":
                level = 2
                break
              case "High":
                level = 3
                break
          }
          if (level > 0) {
            childOn("footwarmer")
            childDimmerLevel("footwarmer", level)
          } else {
            childOff("footwarmer")
          }
          break
        case "outletState":
          if (value == "On") {
            childOn("outlet")
          } else {
            childOff("outlet")
          }
          break
        case "underbedLightState":
          if (value == "On") {
            childOn("underbedlight")
          } else {
            childOff("underbedlight")
          }
          break
        case "underbedLightBrightness":
          // We use 1, 2 or 3 for the dimmer value and this correlates to the array index.
          def dimmerLevel = (UNDERBED_LIGHT_BRIGHTNESS.keySet() as ArrayList).indexOf(value) + 1
          // Note that we don't set the light to on with a dimmer change since
          // the brightness can be set with the light in auto.
          childDimmerLevel("underbedlight", dimmerLevel)
          break
        case "underbedLightTimer":
          // Nothing to send to the child for this as genericComponentDimmer only answers to
          // switch and level events.
          break
      }
      // Send an event with the key name to catalog it and set the attribute.
      sendEvent name: param.key, value: value
    } else {
      logError "Invalid attribute ${param.key}"
    }
  }
}

// Used to set individual device states.
void setStatusOld(Map params) {
  debug "setStatusOld(${params})"
  List<String> validAttributes = device.supportedAttributes.collect{ (String) it.name }
  params.each{param ->
    if (param.key in validAttributes) {
      def attributeValue = device."current${param.key.capitalize()}"
      def value = param.value
      // Translate heat temp to something more meaningful but leave the other values alone
      if (param.key == "footWarmingTemp") {
        value = HEAT_TEMPS.find{ it.value == Integer.valueOf(value) }
        if (value == null) {
          logError "Invalid foot warming temp ${param.value}"
        } else {
          value = value.key
        }
      }
      if (attributeValue.toString() != value.toString()) {
        debug "Setting ${param.key} to ${value}, it was ${attributeValue}"
        // If this is a head or foot device, we need to sync level with the relevant
        // position, if it's presence, then we sync level with the sleep number value.
        if ((state.type == "head" && param.key == "headPosition")
            || (state.type == "foot" && param.key == "footPosition")
            || (state.type == "presence" && param.key == "sleepNumber")) {
          sendEvent name: "level", value: value
        }
        if (state.type != "foot warmer" && param.key == "positionPreset") {
          if (value == "Flat") {
            sendEvent name: "switch", value: "off"
          } else if (value == presetLevel) {
            // On if the level is the desired preset.
            // Note this means it's off even when raised if it doesn't match a preset which
            // may not make sense given there is a level.  But since it can be "turned on"
            // when not at preset level, the behavior (if not the indicator) seems logical.
            sendEvent name: "switch", value: "on"
          }
        }
        if (state.type == "foot warmer" && param.key == "footWarmingTemp") {
          def level = 0
          switch (value) {
              case "Off":
                level = 0
                break
              case "Low":
                level = 1
                break
              case "Medium":
                level = 2
                break
              case "High":
                level = 3
                break
          }
          sendEvent name: "level", value: level
          sendEvent name: "switch", value: level > 0 ? "on" : "off"
        }
        sendEvent name: param.key, value: value
      }
    } else {
      logError "Invalid attribute ${param.key}"
    }
  }
}

void setConnectionState(Boolean connected) {
  if (connected) {
    sendEvent name: "connection", value: "online"
  } else {
    sendEvent name: "connection", value: "offline"
  }
}

Map sendToParent(String method, Object data = null) {
  debug "sending to parent ${method}, ${data}"
  if (device.parentDeviceId) {
    // Send via virtual container method
    return (Map) parent.childComm(method, data, device.deviceNetworkId)
  } else {
    return (Map) parent."${method}"(data, device.deviceNetworkId)
  }
}

void debug(String msg) {
  if (logEnable) {
    logDebug msg
  }
}

//-----------------------------------------------------------------------------
// Methods specific to old device support
//-----------------------------------------------------------------------------

void setType(String val) {
  debug "setType(${val})"
  if (!TYPES.contains(val)) {
    logError "Invalid type ${val}, possible values are ${TYPES}"
  }
  def msg = "${val.capitalize()} - "
  switch (val) {
    case "presence":
      msg += "on/off will switch between preset level (on) and flat (off).  Dimming changes the Sleep Number."
      break
    case "head":
      msg += "on/off will switch between preset level (on) and flat (off).  Dimming changes the head position (0 is flat, 100 is fully raised)."
      break
    case "foot":
      msg += "on/off will switch between preset level (on) and flat (off).  Dimming changes the foot position (0 is flat, 100 is fully raised)."
      break
    case "foot warmer":
      msg += "off switches foot warming off, on will set it to preferred value for preferred time. Dimming changes the heat levels (1: low, 2: medium, 3: high)."
      break
  }
  state.typeInfo = msg
  state.type = val
}

Boolean isPresenceOrParent() {
  return !(String) state.type || (String) state.type == "presence"
}

//-----------------------------------------------------------------------------
// Methods specific to child device support
//-----------------------------------------------------------------------------

DeviceWrapper createChildDevice(String childNetworkId, String componentDriver, String label) {
  // Make sure the child doesn't already exist.
  def child = getChildDevice(childNetworkId)
  if (child) {
    logWarn "Child device with id ${childNetworkId} already exists"
    return child
  } else {
    child = addChildDevice("hubitat", componentDriver, childNetworkId, [label: label, isComponent: false])
    logInfo("Created ${label} child device")
    return child
  }
}

String getChildNetworkId(String name) {
  return device.deviceNetworkId + DNI_SEPARATOR + name
}

void componentRefresh(DeviceWrapper device) {
  poll()
}

String getChildType(String childNetworkId) {
  // network id is $parentId-type
  return childNetworkId.substring(device.deviceNetworkId.length() + 1)
}

void componentOn(DeviceWrapper device) {
  String type = getChildType(device.deviceNetworkId)
  debug "componentOn $type"
  switch (type) {
    case "outlet":
      setOutletState("On")
      break
    case "underbedlight":
      setUnderbedLightState("On", settings.underbedLightTimer)
      break
    case "head":
      // For now, just share the same preset as the parent.
      // TODO: Add "head" preset pref if it turns out people use this.
      logInfo("Head turned on.")
      on()
      break
    case "foot":
      // For now, just share the same preset as the parent.
      // TODO: Add "foot" preset pref if it turns out people use this.
      logInfo("Foot turned on.")
      on()
      break
    case "footwarmer":
      setFootWarmingState(footWarmerLevel, footWarmerTimer)
      break
    default:
      logWarn "Unknown child device type ${type}, not turning on"
      break
  }
}

void componentOff(DeviceWrapper device) {
  String type = getChildType(device.deviceNetworkId)
  debug "componentOff $type"
  switch (type) {
    case "outlet": 
      setOutletState("Off")
      break
    case "underbedlight":
      setUnderbedLightState("Off")
      break
    case "head":
      logInfo("Head turned off, setting bed flat")
      off()
      break
    case "foot":
      logInfo("Foot turned off, setting bed flat")
      off()
      break
    case "footwarmer":
      setFootWarmingState("Off")
      break
    default:
      logWarn "Unknown child device type ${type}, not turning off"
      break
  }
}

void componentSetLevel(DeviceWrapper device, Number level) {
  componentSetLevel(device, level, null)
}

void componentSetLevel(DeviceWrapper device, Number level, Number duration) {
  String type = getChildType(device.deviceNetworkId)
  debug "componentSetLevel $type $level $duration"
  switch (type) {
    case "outlet": 
      logInfo "Child type outlet does not support level"
      break
    case "underbedlight":
      // Only 3 levels are supported.
      String val
      switch (level) {
        case 1:
          val = "Low"
          break
        case 2:
          val = "Medium"
          break
        case 3:
          val = "High"
          break
        default:
          logError "Invalid level for underbed light.  Only 1, 2 or 3 is valid"
          return
      }
      def presetDuration = settings.underbedLightTimer
      if (duration != null && UNDERBED_LIGHT_TIMES.values().contains(duration)) {
        debug "Using provided duration time of ${duration}"
        presetDuration = duration
      }
      debug "Set underbed light on to ${val} for duration ${presetDuration}"
      setUnderbedLightState("On", presetDuration, val)
      break
    case "head":
      setBedPosition(level, ACTUATOR_TYPES.get("head"))
      break
    case "foot":
      setBedPosition(level, ACTUATOR_TYPES.get("foot"))
      break
    case "footwarmer":
      String val
      switch (level) {
        case 1:
          val = "Low"
          break
        case 2:
          val = "Medium"
          break
        case 3:
          val = "High"
          break
        default:
          logError "Invalid level for warmer state.  Only 1, 2 or 3 is valid"
          return
      }
      Number presetDuration = HEAT_TIMES.get(footWarmerTimer)
      if (duration != null && HEAT_TIMES.values().contains(duration.toInteger())) {
        debug "Using provided duration time of ${duration}"
        presetDuration = duration
      }
      debug "Set warmer level to ${val} for ${presetDuration}"
      setFootWarmingState(val, presetDuration)
      break
    default:
      logWarn "Unknown child device type ${type}, not setting level"
      break
  }
}


static Boolean childValueChanged(DeviceWrapper device, String name, Object newValue) {
  String currentValue = device.currentValue(name)
  if (name == "level") {
    return currentValue != null ? currentValue.toInteger() != newValue : true
  } else {
    return currentValue != newValue
  }
}

void childOn(String childType) {
  def child = getChildDevice(getChildNetworkId(childType))
  if (!child) {
    debug "childOn: No child for type ${childType} found"
    return
  }
  if (!childValueChanged(child, "switch", "on")) return
  child.parse([[name:"switch", value:"on", descriptionText: "${child.displayName} was turned on"]])
}

void childOff(String childType) {
  def child = getChildDevice(getChildNetworkId(childType))
  if (!child) {
    debug "childOff: No child for type ${childType} found"
    return
  }
  if (!childValueChanged(child, "switch", "off")) return
  child.parse([[name: "switch", value: "off", descriptionText: "${child.displayName} was turned off"]])
}

void childDimmerLevel(String childType, Number level) {
  def child = getChildDevice(getChildNetworkId(childType))
  if (!child) {
    debug "childDimmerLevel: No child for type ${childType} found"
    return
  }
  if (!childValueChanged(child, "level", level)) return
  child.parse([[name: "level", value: level, descriptionText: "${child.displayName} level was set to ${level}"]])
}

void componentStartLevelChange(DeviceWrapper device, String direction) {
  logInfo "startLevelChange not supported"
}

void componentStopLevelChange(DeviceWrapper device) {
  logInfo "stopLevelChange not supported"
}


/*------------------ Shared constants ------------------*/


@Field static final String appVersion = "3.2.5"  // public version
@Field static final String NAMESPACE = "rvrolyk"
@Field static final String DRIVER_NAME = "Sleep Number Bed"


/*------------------ Logging helpers ------------------*/

@Field static final String PURPLE = "purple"
@Field static final String BLUE = "#0299b1"
@Field static final String GRAY = "gray"
@Field static final String ORANGE = "orange"
@Field static final String RED = "red"

@CompileStatic
private static String logPrefix(String msg, String color = null) {
  StringBuilder sb = new StringBuilder("<span ")
          .append("style='color:").append(GRAY).append(";'>")
          .append("[v").append(appVersion).append("] ")
          .append("</span>")
          .append("<span style='color:").append(color).append(";'>")
          .append(msg)
          .append("</span>")
  return sb.toString()
}

private void logTrace(String msg) {
  log.trace logPrefix(msg, GRAY)
}

private void logDebug(String msg) {
  log.debug logPrefix(msg, PURPLE)
}

private void logInfo(String msg) {
  log.info logPrefix(msg, BLUE)
}

private void logWarn(String msg) {
  log.warn logPrefix(msg, ORANGE)
}

private void logError(String msg, Exception ex = null) {
  log.error logPrefix(msg, RED)
  String a = null
  try {
    if (ex) {
      a = getExceptionMessageWithLine(ex)
    }
  } catch (ignored) {}
  if (a) {
    log.error logPrefix(a, RED)
  }
}

// vim: tabstop=2 shiftwidth=2 expandtab


