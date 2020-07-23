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
import groovy.transform.Field

@Field final ArrayList TYPES = ["presence", "head", "foot", "foot warmer"]
@Field final ArrayList SIDES = ["Right", "Left"]
@Field final Map HEAT_TEMPS = [Off: 0, Low: 31, Medium: 57, High: 72]
@Field final Map HEAT_TIMES = ["30m": 30, "1h": 60, "2h": 120, "3h": 180, "4h": 240, "5h": 300, "6h": 360]
@Field final Map ACTUATOR_TYPES = [head: "H", foot: "F"]
@Field final Map PRESET_TIMES = ["Off": 0, "15m": 15, "30m": 30, "45m": 45, "1h": 60, "2h": 120, "3h": 180]
@Field final Map PRESET_NAMES = [Favorite: 1, Flat: 4, ZeroG: 5, Snore: 6, WatchTV: 3, Read: 2]


metadata {
  definition(name: "Sleep Number Bed",
             namespace: "rvrolyk",
             author: "Russ Vrolyk",
             importUrl: "https://raw.githubusercontent.com/rvrolyk/SleepNumberController/master/SleepNumberController_Driver.groovy"
  ) {
    capability "Switch"
    capability "SwitchLevel"
    capability "PresenceSensor"
    capability "Polling"

    attribute "headPosition", "number"
    attribute "footPosition", "number"
    attribute "footWarmingTemp", "enum", HEAT_TEMPS.collect{ it.key }
    attribute "footWarmingTimer", "enum", HEAT_TIMES.collect{ it.key }
    attribute "sleepNumber", "number"
    // The current preset position of the side
    attribute "positionPreset", "string"
    // The preset that the bed will change to once timer is done
    attribute "positionPresetTimer", "string"
    // The timer for the preset change
    attribute "positionTimer", "number"

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
  }

  preferences {
    section("Settings:") {
      input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
      input name: "presetLevel", type: "enum", title: "Preset level for 'on'", description: "Only valid for device type that is not 'foot warmer'", options: PRESET_NAMES.collect{ it.key }, defaultValue: "Favorite"
      input name: "footWarmerLevel", type: "enum", title: "Warmer level for 'on'", description: "Only valid for device type that is 'foot warmer'", options: HEAT_TEMPS.collect{ it.key }, defaultValue: "Medium"
      input name: "footWarmerTimer", type: "enum", title: "Warmer duration for 'on'", description: "Only valid for device type that is 'foot warmer'", options: HEAT_TIMES.collect{ it.key }, defaultValue: "30m"
    }
  }
}

def installed() {
  debug "installed()"
  updated()
}

def logsOff() {
  log.warn "debug logging disabled..."
  device.updateSetting "logEnable", [value:"false",type:"bool"]
}

def updated() {
  debug "updated()"
  if (logEnable) {
    runIn(1800, logsOff)
  }
  // Poll right after updated / installed
  poll()
}

def parse(String description) {
  debug "parse() - Description: ${description}"
}

// Required by Polling capability
def poll() {
  debug "poll()"
  sendToParent "refreshChildDevices"
}

// Required by Switch capability
def on() {
  sendEvent name: "switch", value: "on"
  if (state.type == "foot warmer") {
    debug "on(): foot warmer"
    setFootWarmingState(footWarmerLevel, footWarmerTimer)
  } else {
    debug "on(): set preset ${presetLevel}"
    setBedPreset(presetLevel)
  }
}

// Required by Switch capability
def off() {
  sendEvent name: "switch", value: "off"
  if (state.type == "foot warmer") {
    debug "off(): foot warmer"
    setFootWarmingState("Off")
  } else {
    debug "off(): set Flat"
    setBedPreset("Flat")
  }
}

// Required by SwitchLevel capability
def setLevel(val) {
  switch (state.type) {
    case "presence":
      debug "setLevel(${val}): sleepNumber"
      setSleepNumbe(val)
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
      def level = 0
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
        log.error "Invalid level for warmer state.  Only 1, 2 or 3 is valid"
        return
      }
      debug "setLevel(${val}): warmer level to ${level}"
      setFootWarmingState(level)
      break;
  }
  sendEvent name: "level", value: val
}

// Required by PresenceSensor capability
def isPresent() {
  return device.currentValue("presence") == "present"
}

def arrived() {
  debug "arrived()"
  if (!isPresent()) {
    log.info "${device.displayName} arrived"
  }
  sendEvent name: "presence", value: "present"
}

def departed() {
  debug "departed()"
  if (isPresent()) {
    log.info "${device.displayName} departed"
  }
  sendEvent name: "presence", value: "not present"
}

def setPresence(val) {
  debug "setPresence(${val})"
  if (val) {
    arrived()
  } else {
    departed()
  }
}

def setBedId(val) {
  debug "setBedId(${va}l)"
  state.bedId = val
}

def setType(val) {
  debug "setType(${val})"
  if (!TYPES.contains(val)) {
    log.error "Invalid type ${val}, possible values are ${TYPES}"
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

def setSide(val) {
  debug "setSide(${val})"
  if (!SIDES.contains(val)) {
    log.error "Invalid side ${val}, possible values are ${SIDES}"
  }
  state.side = val
}

def setRefreshInterval(val) {
  debug "setRefreshInterval(${val})"
  sendToParent "setRefreshInterval", val
}

def setSleepNumber(val) {
  debug "setSleepNumber(${val})"
}

def setBedPosition(val, actuator = null) {
  debug "setBedPosition(${val})"
  def type = actuator ?: ACTUATOR_TYPES.get(state.type)
  if (!type) {
    log.error "Cannot determine actuator"
    return
  }
  if (val >= 0 && val <= 100) {
    sendToParent "setFoundationAdjustment", [actuator: type, position: val]
  } else {
    log.error "Invalid position, must be between 0 and 100"
  }
}

def setFootWarmingState(temp = "OFF", timer = "30m") {
  debug "setWarmerState(${temp}, ${duration})"
  if (HEAT_TEMPS.get(temp) == null) {
    log.error "Invalid warming temp ${temp}"
    return
  }
  if (!HEAT_TIMES.get(timer)) {
    log.error "Invalid warming time ${timer}"
    return
  }
  sendToParent "setFootWarmingState", [temp: HEAT_TEMPS.get(temp), timer: HEAT_TIMES.get(timer)]
}

def setBedPreset(preset) {
  debug "setBedPreset(${preset})"
  if (preset == null || PRESET_NAMES.get(preset) == null) {
    log.error "Invalid preset ${preset}"
    return
  }
  sendToParent "setFoundationPreset", PRESET_NAMES.get(preset)
}

def setBedPresetTimer(preset, timer) {
  debug "setBedPresetTimer(${preset}, ${timer})"
  if (preset == null || PRESET_NAMES.get(preset) == null) {
    log.error "Invalid preset ${preset}"
    return
  }
  if (timer == null || PRESET_TIMES.get(timer) == null) {
    log.error "Invalid preset timer ${timer}"
    return
  }
  sendToParent "setFoundationTimer", [preset: PRESET_NAMES.get(preset), timer: PRESET_TIMES.get(timer)]
}

def stopBedPosition() {
  sendToParent "stopFoundationMovement"
}


// Method used by parent app to set bed state
def setStatus(Map params) {
  debug "setStatus(${params})"
  def validAttributes = device.supportedAttributes.collect{ it.name }
  params.each{param ->
    if (param.key in validAttributes) {
      def attributeValue = device."current${param.key.capitalize()}"
      def value = param.value
      // Translate heat temp to something more meaningful but leave the other values alone
      if (param.key == "footWarmingTemp") {
        value = HEAT_TEMPS.find{ it.value == value }
        if (value == null) {
          log.error "Invalid foot warming temp ${param.value}"
        }
      }
      if (value != attributeValue) {
        debug("Setting ${param.key} to ${value}")
        // If this is a head or foot device, we need to sync level with the relevant position.
        if ((state.type == "head" && param.key == "headPosition") || (state.type == "foot" && param.key == "footPosition")) {
            log.trace "head or foot set level to ${value}"
          sendEvent name: "level", value: value
        }
        if (state.type != "foot warmer" && param.key == "positionPreset") {
          if (value == "Flat") {
            sendEvent name: "switch", value: "off"
          } else if (value == presetLevel) {
            sendEvent name: "switch", value: "on"
          }
        }
        if (state.type == "foot warmer" && param.key == "footWarmingTemp") {
          value = value.key
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
        }
        sendEvent name: param.key, value: value
      }
    } else {
      log.error "Invalid attribute ${param.key}"
    }
  }
}

def sendToParent(method, data = null) {
  debug "sending to parent ${method}, ${data}"
  if (device.parentDeviceId) {
    // Send via virtual container method
    parent.childComm method, data, device.deviceNetworkId
  } else {
    parent."${method}" data, device.deviceNetworkId
  }
}

def debug(msg) {
  if (logEnable) {
    log.debug msg
  }
}

// vim: tabstop=2 shiftwidth=2 expandtab
