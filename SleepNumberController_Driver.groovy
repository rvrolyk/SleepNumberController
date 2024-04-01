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
//file:noinspection SpellCheckingInspection
//file:noinspection unused

import com.hubitat.app.DeviceWrapper
import groovy.transform.CompileStatic
import groovy.transform.Field

#include rvrolyk.SleepNumberLibraryBeta

@Field static final String sLOW = 'Low'
@Field static final String sMED = 'Medium'
@Field static final String sHIGH = 'High'
@Field static final String sFLAT = 'Flat'

@Field static final String sPRESENT = 'present'
@Field static final String sNPRESENT = 'not present'
@Field static final String sLEVEL = 'level'
@Field static final String sSTR = 'string'
@Field static final String sDATE = 'date'

@Field static final String sHEADPOSITION = 'headPosition'
@Field static final String sFOOTPOSITION = 'footPosition'
@Field static final String sFOOTWRMTEMP = 'footWarmingTemp'
@Field static final String sFOOTWRMTIMER = 'footWarmingTimer'
@Field static final String sSLEEPNUM = 'sleepNumber'
@Field static final String sSLEEPNUMFAV = 'sleepNumberFavorite'
@Field static final String sPOSITIONPRESET = 'positionPreset'
@Field static final String sPOSITIONPRETIMER = 'positionPresetTimer'
@Field static final String sPOSITIONTIMER = 'positionTimer'
@Field static final String sPRIVACYMODE = 'privacyMode'
@Field static final String sUNDERBEDLTIMER = 'underbedLightTimer'
@Field static final String sUNDERBEDLSTATE = 'underbedLightState'
@Field static final String sUNDERBEDLBRIGHT = 'underbedLightBrightness'
@Field static final String sOUTLETSTATE = 'outletState'

@Field static final String DNI_SEPARATOR = '-'

@Field static final ArrayList<String> TYPES = ['presence', 'head', 'foot', 'foot warmer']

metadata {
  definition((sNM): DRIVER_NAME,
             namespace: NAMESPACE,
             author: 'Russ Vrolyk',
             importUrl: 'https://raw.githubusercontent.com/rvrolyk/SleepNumberController/master/SleepNumberController_Driver.groovy'
  ) {
    capability 'Actuator'
    capability 'Switch'
    capability 'SwitchLevel'
    capability 'PresenceSensor'
    capability 'Polling'

    // indicator for overall connectivity to Sleep Number API
    attribute 'connection', 'enum', ['online', 'offline']
    attribute sHEADPOSITION, sNUM
    attribute sFOOTPOSITION, sNUM
    attribute sFOOTWRMTEMP, sENUM, HEAT_TEMPS.collect{ it.key }
    attribute sFOOTWRMTIMER, sENUM, HEAT_TIMES.collect{ it.key }
    attribute sSLEEPNUM, sNUM
    // The user's sleep number favorite
    attribute sSLEEPNUMFAV, sNUM
    // The current preset position of the side
    attribute sPOSITIONPRESET, sSTR
    // The preset that the bed will change to once timer is done
    attribute sPOSITIONPRETIMER, sSTR
    // The timer for the preset change
    attribute sPOSITIONTIMER, sNUM
    attribute sPRIVACYMODE, sENUM, [sON, sOFF]
    attribute sUNDERBEDLTIMER, sSTR // String so we can use 'Forever'
    attribute sUNDERBEDLSTATE, sENUM, UNDERBED_LIGHT_STATES
    attribute sUNDERBEDLBRIGHT, sENUM, UNDERBED_LIGHT_BRIGHTNESS.collect{ it.key }
    attribute sOUTLETSTATE, sENUM, OUTLET_STATES
    // Attributes for sleep IQ data
    attribute 'sleepMessage', sSTR
    attribute 'sleepScore', sNUM
    attribute 'restfulAverage', sSTR
    attribute 'restlessAverage', sSTR
    attribute 'heartRateAverage', sNUM
    attribute 'HRVAverage', sNUM
    attribute 'breathRateAverage', sNUM
    attribute 'outOfBedTime', sSTR
    attribute 'inBedTime', sSTR
    attribute 'timeToSleep', sSTR
    attribute 'sessionStart', sDATE
    attribute 'sessionEnd', sDATE
    attribute 'sleepDataRefreshTime', sDATE
    attribute 'sleepIQSummary', sSTR
    attribute 'sessionSummary', sSTR
    // Responsive Air state - optional based on preference since it requires another HTTP request
    // and most users probably don't care about it.
    attribute 'responsiveAir', sENUM, ['true', 'false']
    // Only certain beds have climate control so this will only be present when the feature is detected
    attribute 'coreClimateTemp', sENUM, CORE_CLIMATE_TEMPS
    attribute 'coreClimateTimer', sNUM

    command 'setRefreshInterval', [[(sNM): 'interval', (sTYP): 'NUMBER', constraints: ['NUMBER']]]

    command 'arrived'
    command 'departed'
    command 'setSleepNumber', [[(sNM): 'sleep number', (sTYP): 'NUMBER', constraints: ['NUMBER']]]
    command 'setBedPosition', [[(sNM): 'position', (sTYP): 'NUMBER', constraints: ['NUMBER']],
      [(sNM): 'actuator', (sTYP): 'ENUM', constraints: ACTUATOR_TYPES.collect{ it.value }]]
    command 'setFootWarmingState', [[(sNM): 'temp', (sTYP): 'ENUM', constraints: HEAT_TEMPS.collect{ it.key }],
       [(sNM): 'timer', (sTYP): 'ENUM', constraints: HEAT_TIMES.collect{ it.key }]]
    command 'setBedPreset', [[(sNM): 'preset', (sTYP): 'ENUM', constraints: PRESET_NAMES.collect{ it.key }]]
    command 'setBedPresetTimer', [[(sNM): 'preset', (sTYP): 'ENUM', constraints: PRESET_NAMES.collect{ it.key }],
        [(sNM): 'timer', (sTYP): 'ENUM', constraints: PRESET_TIMES.collect{ it.key }]]
    command 'stopBedPosition'
    command 'enablePrivacyMode'
    command 'disablePrivacyMode'
    command 'getSleepData'
    command 'setSleepNumberFavorite'
    command 'updateSleepNumberFavorite', [[(sNM): 'favorite sleep number', (sTYP): 'NUMBER', constraints: ['NUMBER']]]
    command 'setOutletState', [[(sNM): 'state', (sTYP): 'ENUM', constraints: OUTLET_STATES]]
    command 'setUnderbedLightState', [[(sNM): 'state', (sTYP): 'ENUM', constraints: UNDERBED_LIGHT_STATES],
        [(sNM): 'timer', (sTYP): 'ENUM', constraints: UNDERBED_LIGHT_TIMES.collect{ it.key }],
        [(sNM): 'brightness', (sTYP): 'ENUM', constraints: UNDERBED_LIGHT_BRIGHTNESS.collect{ it.key }]]
    // Works regardless of preference but polling only happens if pref is true
    command 'setResponsiveAirState', [[(sNM): 'state', (sTYP): 'ENUM', constraints: ['true', 'false']]]
    // Only works for beds with CoreClimate
    command 'setCoreClimateState', [[(sNM): 'temperature', (sTYP): 'ENUM', constraints: CORE_CLIMATE_TEMPS],
        [(sNM): 'timer', (sTYP): 'NUMBER', constraints: ['NUMBER']]]
  }

  preferences {
    section('Settings:') {
      input ((sNM): 'logEnable', (sTYP): sBOOL, title: 'Enable debug logging', defaultValue: false)
      input ((sNM): 'presetLevel', (sTYP): sENUM, title: 'Bed preset level for "on"', options: PRESET_NAMES.collect{ it.key }, defaultValue: 'Favorite')
      input ((sNM): 'footWarmerLevel', (sTYP): sENUM, title: 'Foot warmer level for "on"', options: HEAT_TEMPS.collect{ it.key }, defaultValue: sMED)
      input ((sNM): 'footWarmerTimer', (sTYP): sENUM, title: 'Foot warmer duration for "on"', options: HEAT_TIMES.collect{ it.key }, defaultValue: '30m')
      input ((sNM): sUNDERBEDLTIMER, (sTYP): sENUM, title: 'Underbed light timer for "on"', options: UNDERBED_LIGHT_TIMES.collect{ it.key }, defaultValue: '15m')
      input ((sNM): 'enableSleepData', (sTYP): sBOOL, title: 'Enable sleep data collection', defaultValue: false)
      input ((sNM): 'enableResponsiveAir', (sTYP): sBOOL, title: 'Enable responsive air data', defaultValue: false)
    }
  }
}

void installed() {
  debug 'installed()'
  updated()
}

void logsOff() {
  logInfo 'Debug logging disabled...'
  device.updateSetting 'logEnable', [(sVL): 'false', (sTYP): sBOOL]
}

void updated() {
  debug 'updated()'
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
  debug 'poll()'
  sendToParent('refreshChildDevices')
}

// Required by Switch capability
void on() {
  sendEvent ((sNM): sSWITCH, (sVL): sON)
  debug "on(): set preset ${(String)settings.presetLevel}"
  setBedPreset((String)settings.presetLevel)
}

// Required by Switch capability
void off() {
  sendEvent ((sNM): sSWITCH, (sVL): sOFF)
  debug 'off(): set Flat'
  setBedPreset(sFLAT)
}

// setLevel required by SwitchLevel capability
// including one with duration (which we currently ignore).
void setLevel(Number val, Number duration = iZ) {
  debug "setLevel(${val}): sleepNumber"
  setSleepNumber(val)
  sendEvent ((sNM): sLEVEL, (sVL): val)
}

// Required by PresenceSensor capability
Boolean isPresent() {
  return device.currentValue(sPRESENCE) == sPRESENT
}

void arrived() {
  debug 'arrived()'
  if (!isPresent()) {
    logInfo "${device.displayName} arrived"
    sendEvent ((sNM): sPRESENCE, (sVL): sPRESENT)
  }
}

void departed() {
  debug 'departed()'
  if (isPresent()) {
    logInfo "${device.displayName} departed"
    sendEvent ((sNM): sPRESENCE, (sVL): sNPRESENT)
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
  debug "setBedId(${val})"
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
  sendToParent('setRefreshInterval', val)
}

void setSleepNumber(Number val) {
  debug "setSleepNumber(${val})"
  if (val > iZ && val <= 100) {
    sendToParent('setSleepNumber', val)
  } else {
    logError 'Invalid number, must be between 1 and 100'
  }
}

void setBedPosition(Number val, String actuator = sNL) {
  debug "setBedPosition(${val})"
  if (!actuator) {
    logError 'Cannot determine actuator'
    return
  }
  if (val >= iZ && val <= 100) {
    sendToParent('setFoundationAdjustment', [actuator: actuator, position: val])
  } else {
    logError 'Invalid position, must be between 0 and 100'
  }
}

void setFootWarmingState(String temp = sSTOFF, String timer = '30m') {
  debug "setWarmingState(${temp}, ${timer})"
  if (!HEAT_TIMES[timer]) {
    logError "Invalid warming time ${timer}"
    return
  }
  setFootWarmingState(temp, HEAT_TIMES[timer])
}

void setFootWarmingState(String temp = sSTOFF, Number duration) {
  debug "setWarmingState(${temp}, ${duration})"
  if (HEAT_TEMPS[temp] == null) {
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
  sendToParent('setFootWarmingState', [temp: HEAT_TEMPS[temp], timer: duration.toInteger()])
}

void setBedPreset(String preset) {
  debug "setBedPreset(${preset})"
  if (preset == sNL || PRESET_NAMES.get(preset) == null) {
    logError "Invalid bed preset ${preset}"
    return
  }
  sendToParent('setFoundationPreset', PRESET_NAMES.get(preset))
}

void setBedPresetTimer(String preset, String timer) {
  debug "setBedPresetTimer(${preset}, ${timer})"
  if (preset == sNL || PRESET_NAMES.get(preset) == null) {
    logError "Invalid preset name ${preset}"
    return
  }
  if (timer != sNL && PRESET_TIMES.get(timer) == null) {
    logError "Invalid preset timer ${timer}"
    return
  }
  sendToParent('setFoundationTimer', [preset: PRESET_NAMES.get(preset), timer: PRESET_TIMES.get(timer)])
}

void stopBedPosition() {
  debug 'stopBedPostion()'
  sendToParent('stopFoundationMovement')
}

void enablePrivacyMode() {
  debug 'enablePrivacyMode()'
  sendToParent('setPrivacyMode', true)
}

void disablePrivacyMode() {
  debug 'disablePrivacyMode()'
  sendToParent('setPrivacyMode', false)
}

/**
 * Sets the SleepNumber to the preset favorite.
 */
void setSleepNumberFavorite() {
  debug 'setSleepNumberFavorite()'
  sendToParent('setSleepNumberFavorite')
}

void updateSleepNumberFavorite(Number val) {
  debug "updateSleepNumberFavorite(${val})"
  if (val > iZ && val <= 100) {
    sendToParent('updateSleepNumberFavorite', val)
  } else {
    logError 'Invalid number, must be between 1 and 100'
  }
}

void setOutletState(String st) {
  debug "setOutletState(${st})"
  if (st == sNL || !OUTLET_STATES.contains(st)) {
    logError "Invalid outlet state ${st}"
    return
  }
  sendToParent('setOutletState', st)
}

void setUnderbedLightState(String st, String timer = 'Forever', String brightness = sHIGH) {
  debug "setUnderbedLightState(${st}, ${timer}, ${brightness})"
  if (st == sNL || !UNDERBED_LIGHT_STATES.contains(st)) {
    logError "Invalid lighting state ${st}"
    return
  }
  if (timer == sNL || UNDERBED_LIGHT_TIMES.get(timer) == null) {
    logError "Invalid lighting timer ${timer}"
    return
  }
  if (brightness == sNL || UNDERBED_LIGHT_BRIGHTNESS[brightness] == null) {
    logError "Invalid brightness ${brightness}"
    return
  }
  sendToParent('setUnderbedLightState', [
    state: st,
    timer: UNDERBED_LIGHT_TIMES[timer],
    brightness: UNDERBED_LIGHT_BRIGHTNESS[brightness] ])
}

void setResponsiveAirState(String state) {
  debug "setResponsiveAirState($state)"
  sendToParent 'setResponsiveAirState', Boolean.valueOf(state)
}

void setCoreClimateState(String temp, Number timer) {
  debug "setCoreClimateState($temp, $timer)"
  if (!CORE_CLIMATE_TEMPS.contains(temp)) {
    logError "Invalid temp ${temp}, valid options are ${CORE_CLIMATE_TEMPS}"
    return
  }
  sendToParent('setCoreClimateSettings', [
    'preset': temp,
    'timer': timer
  ])
}

void getSleepData() {
  Map data = sendToParent('getSleepData')
  debug "sleep data ${data}"

  if (!data || data.sleepSessionCount == iZ) {
    logInfo 'No sleep sessions found, skipping update'
    return
  }

  // Set basic attributes
  // device.currentValue(name, true) doesn't seem to avoid the cache so stash the values
  // used in the summary tiles.
  sendEvent((sNM): 'sleepDataRefreshTime', (sVL): new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX"))
  sendEvent((sNM): 'sleepMessage', (sVL): data.sleepData.message.find{it != ''})
  def sleepScore = data.sleepIQAvg
  sendEvent((sNM): 'sleepScore', (sVL): sleepScore)
  String restfulAvg = convertSecondsToTimeString((Integer)data.restfulAvg)
  sendEvent((sNM): 'restfulAverage', (sVL): restfulAvg)
  String restlessAvg = convertSecondsToTimeString((Integer)data.restlessAvg)
  sendEvent((sNM): 'restlessAverage', (sVL): restlessAvg)
  def heartRateAvg = data.heartRateAvg
  sendEvent((sNM): 'heartRateAverage', (sVL): heartRateAvg)
  def hrvAvg = data.hrvAvg
  sendEvent((sNM): 'HRVAverage', (sVL): hrvAvg)
  def breathRateAvg = data.respirationRateAvg
  sendEvent((sNM): 'breathRateAverage', (sVL): breathRateAvg)
  def outOfBedTime = convertSecondsToTimeString((Integer)data.outOfBedTotal)
  sendEvent((sNM): 'outOfBedTime', (sVL): outOfBedTime)
  def inBedTime = convertSecondsToTimeString((Integer)data.inBedTotal)
  sendEvent((sNM): 'inBedTime', (sVL): inBedTime)
  String timeToSleep = convertSecondsToTimeString((Integer)data.fallAsleepPeriod)
  sendEvent((sNM): 'timeToSleep', (sVL): timeToSleep)
  List<Map> slpsess= (List<Map>)((List<Map>)data.sleepData)[iZ].sessions
  sendEvent((sNM): 'sessionStart', (sVL): slpsess[iZ].startDate)
  sendEvent((sNM): 'sessionEnd', (sVL): slpsess[slpsess.size() - i1].endDate)

  String table = '<table class="sleep-tiles %extraClasses" style="width:100%;font-size:12px;font-size:1.5vmax" id="%id">'
  // Set up tile attributes
  // Basic tile to represent what app shows when launched: last score, heart rate, hrv, breath rate
  String iqTile; iqTile = table.replaceFirst('%id', "sleepiq-summary-${((String)device.getLabel()).toLowerCase().replaceAll(" ", "_")}")
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
  sendEvent((sNM): "sleepIQSummary", (sVL): iqTile)
  // Basic tile to aggregate session stats: time in bed, time to sleep, restful, restless, bed exits
  String summaryTile
  summaryTile = table.replaceFirst('%id', "session-summary-${((String)device.getLabel()).toLowerCase().replaceAll(" ", "_")}")
      .replaceFirst('%extraClasses', "session-summary")
  summaryTile += "<tr><td colspan = 2>In bed for ${inBedTime}</td></tr>"
  summaryTile += '<tr><th style="text-align: center; width: 50%">Time to fall asleep</th><th style="text-align: center">Restful</th></tr>'
  summaryTile += '<tr><td style="text-align: center">' + timeToSleep + '</td>'
  summaryTile += '<td style="text-align: center">' + restfulAvg + '</td></tr>'
  summaryTile += '<tr><th style="text-align: center">Restless</th><th style="text-align: center">Bed Exit</th></tr>'
  summaryTile += '<tr><td style="text-align: center">' + restlessAvg + '</td>'
  summaryTile += '<td style="text-align: center">' + outOfBedTime + '</td>'
  summaryTile += '</tr></table>'
  sendEvent((sNM): 'sessionSummary', (sVL): summaryTile)
}

static String convertSecondsToTimeString(Integer secondsToConvert) {
  new GregorianCalendar(0, 0, 0, 0, 0, secondsToConvert, 0).time.format('HH:mm:ss')
}

// Method used by parent app to set bed state
void setStatus(Map<String,Object> params) {
  debug "setStatus(${params})"
  List<String> validAttributes = device.supportedAttributes.collect{ (String) it.name }
  for (Map.Entry<String,Object>param in params) {
    String pk = param.key
    if (pk in validAttributes) {
      def value; value = param.value
      // Translate some of the values into something more meaningful for comparison
      // but leave the other values alone
      if (pk == sFOOTWRMTEMP) {
        Map.Entry<String, Integer> aa = HEAT_TEMPS.find{ it.value == Integer.valueOf("${value}") }
        value = aa ? aa.key : sNL
        if (value == sNL) {
          logError "Invalid foot warming temp ${param.value}"
          continue
        }
      } else if (pk == sUNDERBEDLBRIGHT) {
        Map.Entry<String, Integer> aa = UNDERBED_LIGHT_BRIGHTNESS.find { it.value == value as Integer }
        value = aa ? aa.key : sNL
        if (value == sNL) {
          logWarn "Invalid underbedLightBrightness ${param.value}, using Low"
          value = sLOW
        }
      }

      def attributeValue = device."current${pk.capitalize()}"
      if (attributeValue.toString() != value.toString()) {
        debug "Setting ${pk} to ${value}, it was ${attributeValue}"
      }
      // Figure out what child device to send to based on the key.
      Boolean defaultDone; defaultDone = false
      switch (pk) {
        case sPRESENCE:
          setPresence((Boolean)value)
          defaultDone = true
          break
        case sSLEEPNUM:
          // This is for this device so just send the event.
          sendEvent ((sNM): sLEVEL, (sVL): value)
          break
        case sPOSITIONPRESET:
          if (value == sFLAT) {
            sendEvent ((sNM): sSWITCH, (sVL): sOFF)
          } else if (value == (String) settings.presetLevel) {
            // On if the level is the desired preset.
            // Note this means it's off even when raised if it doesn't match a preset which
            // may not make sense given there is a level.  But since it can be 'turned on'
            // when not at preset level, the behavior (if not the indicator) seems logical.
            sendEvent ((sNM): sSWITCH, (sVL): sON)
          }
          break
        case sHEADPOSITION:
          childDimmerLevel(sHEAD, value as Integer)
          break
        case sFOOTPOSITION:
          childDimmerLevel(sFOOT, value as Integer)
          break
        case sFOOTWRMTEMP:
          Integer level; level = iZ
          switch (value) {
              case sSTOFF:
                level = iZ
                break
              case sLOW:
                level = i1
                break
              case sMED:
                level = 2
                break
              case sHIGH:
                level = 3
                break
          }
          if (level > iZ) {
            childOn(sFOOTWMR)
            childDimmerLevel(sFOOTWMR, level)
          } else {
            childOff(sFOOTWMR)
          }
          break
        case sOUTLETSTATE:
          if (value == sSTON) {
            childOn(sOUTLET)
          } else {
            childOff(sOUTLET)
          }
          break
        case sUNDERBEDLSTATE:
          if (value == sSTON) {
            childOn(sUNDERBEDLIGHT)
          } else {
            childOff(sUNDERBEDLIGHT)
          }
          break
        case sUNDERBEDLBRIGHT:
          // We use 1, 2 or 3 for the dimmer value and this correlates to the array index.
          Integer dimmerLevel = (UNDERBED_LIGHT_BRIGHTNESS.keySet() as ArrayList).indexOf(value)
          // Note that we don't set the light to on with a dimmer change since
          // the brightness can be set with the light in auto.
          childDimmerLevel(sUNDERBEDLIGHT, dimmerLevel)
          break
        case sUNDERBEDLTIMER:
          // Nothing to send to the child for this as genericComponentDimmer only answers to
          // switch and level events.
          break
      }
      if (!defaultDone) {
        // Send an event with the key name to catalog it and set the attribute.
        sendEvent((sNM): pk, (sVL): value)
      }
    } else {
      logError "Invalid status attribute ${pk}"
    }
  }
}

void setConnectionState(Boolean connected) {
  // sendEvent checks if value is unchanged and does nothing automatically
  sendEvent((sNM): 'connection', (sVL): connected ? 'online' : 'offline')
}

Map sendToParent(String method, Object data = null) {
  debug "sending to parent ${method}, ${data}"
  return (Map) parent."${method}"(data, (String)device.deviceNetworkId)
}

void debug(String msg) {
  if ((Boolean) settings.logEnable) {
    logDebug msg
  }
}

@Field static final String sBLANK = ''
@Field static final String sLINEBR = '<br>'

static String span(String str, String clr = sNL, String sz = sNL, Boolean bld = false, Boolean br = false) {
  return str ? "<span ${(clr || sz || bld) ? "style = '${clr ? "color: ${clr};" : sBLANK}${sz ? "font-size: ${sz};" : sBLANK}${bld ? "font-weight: bold;" : sBLANK}'" : sBLANK}>${str}</span>${br ? sLINEBR : sBLANK}" : sBLANK
}

//-----------------------------------------------------------------------------
// Methods specific to child device support
//-----------------------------------------------------------------------------

DeviceWrapper createChildDevice(String childNetworkId, String componentDriver, String label) {
  // Make sure the child doesn't already exist.
  DeviceWrapper child
  child = gChildDevice(childNetworkId)
  if(child) {
    logWarn "Child device with id ${childNetworkId} already exists"
  } else {
    child = (DeviceWrapper)addChildDevice('hubitat', componentDriver, childNetworkId, [label: label, isComponent: false])
    logInfo("Created ${label} child device")
  }
  return child
}

String getChildNetworkId(String name) {
  return (String)device.deviceNetworkId + DNI_SEPARATOR + name
}

void componentRefresh(DeviceWrapper device) {
  poll()
}

String getChildType(String childNetworkId) {
  // network id is $parentId-type
  return childNetworkId.substring(((String)device.deviceNetworkId).length() + i1)
}

void componentOn(DeviceWrapper device) {
  String type = getChildType((String)device.deviceNetworkId)
  debug "componentOn $type"
  switch (type) {
    case sOUTLET:
      setOutletState(sSTON)
      break
    case sUNDERBEDLIGHT:
      setUnderbedLightState(sSTON, (String)settings[sUNDERBEDLTIMER])
      break
    case sHEAD:
      // For now, just share the same preset as the parent.
      // TODO: Add 'head' preset pref if it turns out people use this.
      logInfo('Head turned on.')
      on()
      break
    case sFOOT:
      // For now, just share the same preset as the parent.
      // TODO: Add 'foot' preset pref if it turns out people use this.
      logInfo('Foot turned on.')
      on()
      break
    case sFOOTWMR:
      setFootWarmingState((String)settings.footWarmerLevel, (String)settings.footWarmerTimer)
      break
    default:
      logWarn "Unknown child device type ${type}, not turning on"
      break
  }
}

void componentOff(DeviceWrapper device) {
  String type = getChildType((String)device.deviceNetworkId)
  debug "componentOff $type"
  switch (type) {
    case sOUTLET:
      setOutletState(sSTOFF)
      break
    case sUNDERBEDLIGHT:
      setUnderbedLightState(sSTOFF)
      break
    case sHEAD:
      logInfo('Head turned off, setting bed flat')
      off()
      break
    case sFOOT:
      logInfo('Foot turned off, setting bed flat')
      off()
      break
    case sFOOTWMR:
      setFootWarmingState(sSTOFF)
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
  def type = getChildType((String)device.deviceNetworkId)
  debug "componentSetLevel $type $level $duration"
  switch (type) {
    case sOUTLET:
      logInfo 'Child type outlet does not support level'
      break
    case sUNDERBEDLIGHT:
      // Only 3 levels are supported.
      String val
      switch (level) {
        case i1:
          val = sLOW
          break
        case 2:
          val = sMED
          break
        case 3:
          val = sHIGH
          break
        default:
          logError 'Invalid level for underbed light.  Only 1, 2 or 3 is valid'
          return
      }
      String presetDuration; presetDuration = (String)settings[sUNDERBEDLTIMER]
      if (duration != null && UNDERBED_LIGHT_TIMES.values().contains(duration)) {
        debug "Using provided duration time of ${duration}"
        presetDuration = UNDERBED_LIGHT_TIMES.find{ it.value==duration }.key
      }
      debug "Set underbed light on to ${val} for duration ${presetDuration}"
      setUnderbedLightState(sSTON, presetDuration, val)
      break
    case sHEAD:
      setBedPosition(level, ACTUATOR_TYPES.get(sHEAD))
      break
    case sFOOT:
      setBedPosition(level, ACTUATOR_TYPES.get(sFOOT))
      break
    case sFOOTWMR:
      String val
      switch (level) {
        case i1:
          val = sLOW
          break
        case 2:
          val = sMED
          break
        case 3:
          val = sHIGH
          break
        default:
          logError 'Invalid level for warmer state.  Only 1, 2 or 3 is valid'
          return
      }
      Number presetDuration; presetDuration = HEAT_TIMES[(String)settings.footWarmerTimer]
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
  if (name == sLEVEL) {
    return currentValue != sNL ? currentValue.toInteger() != newValue : true
  } else {
    return currentValue != newValue
  }
}

DeviceWrapper gChildDevice(String netId){ return (DeviceWrapper)getChildDevice(netId) ?: null }

void childOn(String childType) {
  DeviceWrapper child = gChildDevice(getChildNetworkId(childType))
  if (!child) {
    debug "childOn: No child for type ${childType} found"
    return
  }
  if (!childValueChanged(child, sSWITCH, sON)) return
  child.parse([[(sNM):sSWITCH, (sVL):sON, descriptionText: "${child.displayName} was turned on"]])
}

void childOff(String childType) {
  DeviceWrapper child = gChildDevice(getChildNetworkId(childType))
  if (!child) {
    debug "childOff: No child for type ${childType} found"
    return
  }
  if (!childValueChanged(child, sSWITCH, sOFF)) return
  child.parse([[(sNM): sSWITCH, (sVL): sOFF, descriptionText: "${child.displayName} was turned off"]])
}

void childDimmerLevel(String childType, Number level) {
  DeviceWrapper child = gChildDevice(getChildNetworkId(childType))
  if (!child) {
    debug "childDimmerLevel: No child for type ${childType} found"
    return
  }
  if (!childValueChanged(child, sLEVEL, level)) return
  child.parse([[(sNM): sLEVEL, (sVL): level, descriptionText: "${child.displayName} level was set to ${level}"]])
}

void componentStartLevelChange(DeviceWrapper device, String direction) {
  logInfo 'startLevelChange not supported'
}

void componentStopLevelChange(DeviceWrapper device) {
  logInfo 'stopLevelChange not supported'
}

// vim: tabstop=2 shiftwidth=2 expandtab


