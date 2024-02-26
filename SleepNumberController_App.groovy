/**
 *  Sleep Number Controller App
 *
 *  Usage:
 *    Allows controlling Sleep Number Flexible bases including presence detection.
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
 *  of those but leverages prior work they've done for the API calls and bed management.
 *    https://github.com/natecj/SmartThings/blob/master/smartapps/natecj/sleepiq-manager.src/sleepiq-manager.groovy
 *    https://github.com/ClassicTim1/SleepNumberManager/blob/master/FlexBase/SmartApp.groovy
 *    https://github.com/technicalpickles/sleepyq/blob/master/sleepyq/__init__.py
 */
//file:noinspection unused
//file:noinspection SpellCheckingInspection
//file:noinspection GrMethodMayBeStatic

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.*
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.time.*

import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.regex.Pattern
import org.json.JSONObject
import java.text.SimpleDateFormat

@Field static ConcurrentLinkedQueue requestQueue = new ConcurrentLinkedQueue()
@Field static Semaphore mutex = new Semaphore(1)
@Field volatile static Long lastLockTime = 0L
@Field static Long lastErrorLogTime = 0L

@Field static final String sNL = (String)null
@Field static final String sSTON = 'On'
@Field static final String sSTOFF = 'Off'
@Field static final String sRIGHT = 'Right'
@Field static final String sLEFT = 'Left'
@Field static final String sPAUSED = 'paused'
@Field static final String sACTIVE = 'active'

@Field static final String sNUM = 'number'
@Field static final String sTXT = 'text'
@Field static final String sENUM = 'enum'
@Field static final String sBOOL = 'bool'
@Field static final String sON = 'on'
@Field static final String sOFF = 'off'
@Field static final String sSWITCH = 'switch'
@Field static final String sPRESENCE = 'presence'
@Field static final String sNM = 'name'
@Field static final String sVL = 'value'
@Field static final String sTYP = 'type'
@Field static final String sGEN = 'generation'
@Field static final String sACCT_ID = 'accountId'
@Field static final String sTIT = 'title'
@Field static final String sDESC = 'description'

@Field static final String sHEAD = 'head'
@Field static final String sFOOT = 'foot'
@Field static final String sFOOTWMR = 'foot warmer'
@Field static final String sOUTLET = 'outlet'
@Field static final String sUNDERBEDLIGHT = 'underbedlight'

@Field static final String sSIDE = 'side'
@Field static final String sBEDID = 'bedId'
@Field static final String sDEVICEID = 'deviceId'

@Field static final String sCACHE = ' CACHE'

@Field static final String sREFRESHCHILDDEVICES = 'refreshChildDevices'
@Field static final String sLASTFAMILYDATA = 'lastFamilyDataUpdDt'
@Field static final String sLASTBEDDATA = 'lastBedDataUpdDt'

@Field static final Integer iZ = 0
@Field static final Integer i1 = 1
@Field static final Integer i2 = 2
@Field static final Integer i3 = 3
@Field static final Integer i4 = 4
@Field static final Integer i20 = 20

@Field static final String API_HOST = 'prod-api.sleepiq.sleepnumber.com'
static String getAPI_URL() { 'https://' + API_HOST }
@Field static final String LOGIN_HOST = 'l06it26kuh.execute-api.us-east-1.amazonaws.com'
static String getLOGIN_URL() { 'https://' + LOGIN_HOST }
@Field static final String LOGIN_CLIENT_ID = 'jpapgmsdvsh9rikn4ujkodala'
@Field static final String USER_AGENT = 'SleepIQ/1669639706 CFNetwork/1399 Darwin/22.1.0'
@Field static final String SN_APP_VERSION = '4.8.40'

// TODO: Figure out how to share relevant values with the driver
@Field static final Map<String, String> VALID_ACTUATORS = ['H': 'Head', 'F': 'Foot']
@Field static final ArrayList<Integer> VALID_SPEEDS = [0, 1, 2, 3]
@Field static final ArrayList<Integer> VALID_WARMING_TIMES = [30, 60, 120, 180, 240, 300, 360]
@Field static final Map<Integer, String> VALID_WARMING_TEMPS = [0: 'off', 31: 'low', 57: 'medium', 72: 'high']
@Field static final ArrayList<Integer> VALID_PRESET_TIMES = [0, 15, 30, 45, 60, 120, 180]
@Field static final Map<Integer, String> VALID_PRESETS = [1: 'favorite', 2: 'read', 3: 'watch_tv', 4: 'flat', 5: 'zero_g', 6: 'snore']
@Field static final ArrayList<Integer> VALID_LIGHT_TIMES = [15, 30, 45, 60, 120, 180]
@Field static final ArrayList<Integer> VALID_LIGHT_BRIGHTNESS = [1, 30, 100]
@Field static final Map<String, String> LOG_LEVELS = ['0': 'Off', '1': 'Debug', '2': 'Info', '3': 'Warn']
@Field static final Map<String, String> BAM_KEY = [
  'HaltAllActuators': 'ACHA',
  'GetSystemConfiguration': 'SYCG',
  'SetSleepiqPrivacyState': 'SPRS',
  'GetSleepiqPrivacyState': 'SPRG',
  'InterruptSleepNumberAdjustment': 'PSNI',
  'StartSleepNumberAdjustment': 'PSNS',
  'GetSleepNumberControls': 'SNCG',
  'SetFavoriteSleepNumber': 'SNFS',
  'GetFavoriteSleepNumber': 'SNFG',
  'SetUnderbedLightSettings': 'UBLS',
  'SetUnderbedLightAutoSettings': 'UBAS',
  'GetUnderbedLightSettings': 'UBLG',
  'GetUnderbedLightAutoSettings': 'UBAG',
  'GetActuatorPosition': 'ACTG',
  'SetActuatorTargetPosition': 'ACTS',
  'SetTargetPresetWithoutTimer': 'ASTP',
  'SetTargetPresetWithTimer': 'ACSP',
  'GetCurrentPreset': 'AGCP',
  'SetResponsiveAirState': 'LRAS',
  'GetResponsiveAirState': 'LRAG',
  'SetFootWarming': 'FWTS',
  'GetFootWarming': 'FWTG',
]

@Field static List<String> FEATURE_NAMES = [
        "bedType", // seems to always be 'dual'?  Maybe on some beds it's single?
        "pressureControlEnabledFlag",
        "articulationEnableFlag",
        "underbedLightEnableFlag",
        "rapidSleepSettingEnableFlag",
        "thermalControlEnabledFlag",
        "rightHeadActuator",
        "rightFootActuator",
        "leftHeadActuator",
        "leftFootActuator",
        "flatPreset",
        "favoritePreset",
        "snorePreset",
        "zeroGravityPreset",
        "watchTvPreset",
        "readPreset",
]

@Field static final String PAUSE = 'Pause'
@Field static final String RESUME = 'Resume'

@CompileStatic
static Boolean devdbg() { return false }

definition(
  (sNM): APP_NAME,
  namespace: NAMESPACE,
  author: 'Russ Vrolyk',
  (sDESC): 'Control your Sleep Number Flexfit bed.',
  category: 'Integrations',
  iconUrl: sBLK,
  iconX2Url: sBLK,
  importUrl: 'https://raw.github.com/rvrolyk/SleepNumberController/master/SleepNumberController_App.groovy'
)

preferences {
  page ((sNM): 'homePage', install: true, uninstall: true)
  page ((sNM): 'findBedPage')
  page ((sNM): 'selectBedPage')
  page ((sNM): 'createBedPage')
  page ((sNM): 'diagnosticsPage')
}

/**
 * Required handler for pause button.
 */
def appButtonHandler(btn) {
  if (btn == 'pause') {
    state.paused = !(Boolean) state.paused
    if ((Boolean) state.paused) {
      debug 'Paused, unscheduling...'
      unschedule()
      unsubscribe()
      updateLabel()
    } else {
      login()
      initialize()
    }
  }
}

Map homePage() {
  List<Map> currentDevices = getBedDeviceData()

  dynamicPage((sNM): 'homePage') {
    section(sBLK) {
      input ((sNM): 'pause', (sTYP): 'button', (sTIT): (Boolean)state.paused ? RESUME : PAUSE)
    }
    section('<b>Settings</b>') {
      input ((sNM): 'login', (sTYP): sTXT, (sTIT): 'sleepnumber.com email',
          (sDESC): 'Email address you use with Sleep Number', submitOnChange: true)
      input ((sNM): 'password', (sTYP): 'password', (sTIT): 'sleepnumber.com password',
          (sDESC): 'Password you use with Sleep Number', submitOnChange: true)
      // User may opt for constant refresh or a variable one.
      Boolean defaultVariableRefresh = gtSetting('variableRefresh') != null && !getSettingB('variableRefresh') ? false : getSettingI('refreshInterval') == null
      input ('variableRefresh', sBOOL, (sTIT): 'Use variable refresh interval? (recommended)', defaultValue: defaultVariableRefresh,
         submitOnChange: true)
      if (defaultVariableRefresh || getSettingB('variableRefresh')) {
        input ((sNM): 'dayInterval', (sTYP): sNUM, (sTIT): 'Daytime Refresh Interval (minutes; 1-59)',
            (sDESC): 'How often to refresh bed state during the day', defaultValue: 30)
        input ((sNM): 'nightInterval', (sTYP): sNUM, (sTIT): 'Nighttime Refresh Interval (minutes; 1-59)',
              (sDESC): 'How often to refresh bed state during the night', defaultValue: i1)
        input 'variableRefreshModes', sBOOL, (sTIT): 'Use modes to control variable refresh interval', defaultValue: false, submitOnChange: true
        if (getSettingB('variableRefreshModes')) {
          input ((sNM): 'nightMode', (sTYP): 'mode', (sTIT): 'Modes for night (anything else will be day)', multiple: true, submitOnChange: true)
          app.removeSetting('dayStart')
          app.removeSetting('nightStart')
        } else {
          input ((sNM): 'dayStart', (sTYP): 'time', (sTIT): 'Day start time',
              (sDESC): 'Time when day will start if both sides are out of bed for more than 5 minutes', submitOnChange: true)
          input ((sNM): 'nightStart', (sTYP): 'time', (sTIT): 'Night start time', (sDESC): 'Time when night will start', submitOnChange: true)
          app.removeSetting('nightMode')
        }
        app.removeSetting('refreshInterval')
      } else {
        input ((sNM): 'refreshInterval', (sTYP): sNUM, (sTIT): 'Refresh Interval (minutes; 1-59)',
            (sDESC): 'How often to refresh bed state', defaultValue: i1)
        app.removeSetting('dayInterval')
        app.removeSetting('nightInterval')
        app.removeSetting('dayStart')
        app.removeSetting('nightStart')
        app.removeSetting('nightMode')
        app.removeSetting('variableRefreshModes')
      }
    }

    section('<b>Bed Management</b>') {
      if (!getSettingStr('login') || !getSettingStr('password')) {
        paragraph 'Add login and password to find beds'
      } else {
        if (currentDevices.size() > iZ) {
          paragraph 'Current beds'
          for (Map device in currentDevices) {
            String output; output = sBLK
            if ((Boolean)device.isChild) {
              output += '            '
            } else {
              output += device[sBEDID]
            }
            output += " (<a href=\"/device/edit/${device[sDEVICEID]}\">dev:${device[sDEVICEID]}</a>) / ${device[sNM]} / ${device[sSIDE]} / ${device[sTYP]}"
            paragraph output
          }
          paragraph '<br>Note: <i>To remove a device remove it from the Devices list</i>'
        }
        // Only show bed search if user entered creds
        if (getSettingStr('login') && getSettingStr('password')) {
          href 'findBedPage', (sTIT): 'Create or Modify Bed', (sDESC): 'Search for beds'
        }
      }
    }

    section((sTIT): sBLK) {
      href url: 'https://github.com/rvrolyk/SleepNumberController', style: 'external', required: false, (sTIT): 'Documentation', (sDESC): 'Tap to open browser'
    }
 
    section((sTIT): sBLK) {
      href url: 'https://www.paypal.me/rvrolyk', style: 'external', required: false, (sTIT): 'Donations', (sDESC): 'Tap to open browser for PayPal'
    }
       
    section((sTIT): '<b>Advanced Settings</b>') {
      String defaultName; defaultName = APP_NAME
      if ((String) state.displayName) {
        defaultName = (String) state.displayName
      }
      app.updateLabel(defaultName)

      label ((sTIT): 'Assign an app name', required: false, defaultValue: defaultName)
      input ((sNM): 'modes', (sTYP): 'mode', (sTIT): 'Poll only in these mode(s)', required: false, multiple: true, submitOnChange: true)
      input ((sNM): 'switchToDisable', (sTYP): 'capability.switch', (sTIT): 'Switch to disable refreshes', required: false, submitOnChange: true)
      input 'enableDebugLogging', sBOOL, (sTIT): 'Enable debug logging for 30m?', defaultValue: false, required: true, submitOnChange: true
      input 'logLevel', sENUM, (sTIT): 'Choose the logging level', defaultValue: '2', submitOnChange: true, options: LOG_LEVELS
      input 'limitErrorLogsMin', sNUM, (sTIT): 'How long between error log reports (minutes), 0 for no limit. <br><font size=-1>(Only applies when log level is not off)</font> ', defaultValue: 0, submitOnChange: true
      if (getSettingStr('login') && getSettingStr('password')) {
        href 'diagnosticsPage', (sTIT): 'Diagnostics', (sDESC): 'Show diagnostic info'
      }
      input 'useAwsOAuth', sBOOL, (sTIT): '(Beta) Use AWS OAuth', required: false, submitOnChange: true, defaultValue: false
    }
  }
}

def installed() {
  initialize()
  state.paused = false
}

def updated() {
  unsubscribe()
  unschedule()
  app.removeSetting('logEnable')
  app.removeSetting('hubitatQueryString')
  app.removeSetting('requestType')
  app.removeSetting('requestPath')
  app.removeSetting('requestBody')
  app.removeSetting('requestQuery')
  app.removeSetting('newDeviceName')
  state.remove('variableRefresh')
  state.remove('selectBedP')
  state.remove('createBedP')
  state.remove('diagP')
  state.session = null // next run will refresh all tokens/cookies
  state.remove('pauseButtonName')
  initialize()
  if (getSettingB('enableDebugLogging')) {
    wrunIn(1800L, 'logsOff')
  }
}

void logsOff() {
  if (getSettingB('enableDebugLogging')) {
    // Log this information regardless of user setting.
    logInfo 'debug logging disabled...'
    app.updateSetting 'enableDebugLogging', [(sVL): 'false', (sTYP): sBOOL]
  }
}

def initialize() {
  Integer interval = getSettingI('refreshInterval')
  if (interval <= iZ && !getSettingB('variableRefresh')) {
    logError "Invalid refresh interval ${interval}"
  }
  Integer day = getSettingI('dayInterval')
  Integer night = getSettingI('nightInterval')
  if (getSettingB('variableRefresh') && (day <= iZ || night <= iZ)) {
    logError "Invalid refresh intervals ${day} or ${night}"
  }
  if (getSettingB('variableRefreshModes')) {
    subscribe(location, 'mode', configureVariableRefreshInterval)
  }
  subscribe(location, 'systemStart', startHandler)

  remTsVal(sLASTBEDDATA)
  remTsVal(sLASTFAMILYDATA)
  remTsVal('lastPrivacyDataUpdDt')
  remTsVal('lastSleepFavoriteUpdDt')
  remTsVal('lastSleeperDataUpdDt')
  remTsVal('lastFoundationSystemUpdDt')
  outletMapFLD = [:]

  setRefreshInterval(new BigDecimal(iZ) /* force picking from settings */, "" /* ignored */)
  initializeBedInfo()
  refreshChildDevices()
  updateLabel()
}

void startHandler(evt) {
  debug 'startHandler called'
  wrunIn(40L, 'startAction')
}

void startAction() {
  scheduledRefreshChildDevices()
}

void updateLabel() {
  // Store the user's original label in state.displayName
  String appLabel; appLabel = (String) app.label
  Boolean connected; connected = false
  String dispN; dispN = (String) state.displayName
  String span = ' <span style=color:'
  if (dispN && dispN.contains(span)) { dispN = dispN.split(span)[iZ] }
  if (appLabel.contains(span)) { appLabel = appLabel.split(span)[iZ]  }
  if (!appLabel) { appLabel = APP_NAME }
  if (dispN != appLabel) { state.displayName = appLabel }

  String status; status = (String) state.status
  Boolean paused = (Boolean) state.paused
  if (status || paused) {
    String nstatus; nstatus = status
    StringBuilder label; label = new StringBuilder((String)state.displayName + span)
    if (paused) {
      nstatus = '(Paused)'
      label.append('red')
    } else if (status == 'Online') {
      label.append('green')
      connected = true
    } else if (status.contains('Login')) {
      label.append('red')
    } else {
      label.append('orange')
    }
    label.append(">${nstatus}</span>")
    app.updateLabel(label.toString())
    for (ChildDeviceWrapper b in getBedDevices()) { b.setConnectionState(connected) }
  }
}

/*------------------ Bed state helpers  ------------------*/
static String getBedDeviceId(ChildDeviceWrapper bed) {
  return (String)((Map) bed.getState())[sBEDID]
}

static String getBedDeviceSide(ChildDeviceWrapper bed) {
  return (String)((Map) bed.getState())[sSIDE]
}

static String getBedDeviceType(ChildDeviceWrapper bed) {
  return (String)((Map) bed.getState())[sTYP]
}

/**
 * rest calls
 *     getBeds() (C)
 *  @return   recreates state.bedInfo
 */
@CompileStatic
void initializeBedInfo() {
  debug 'Setting up bed info'
  Map bedInfo = getBeds()
  Map<String, Map> stateBedInfo = [:]
  if (bedInfo) {
    for (Map bed in (List<Map>)bedInfo.beds) {
      String id = bed[sBEDID].toString()
      if (devdbg()) debug('Bed id %s', id)
      if (!stateBedInfo.containsKey(id)) {
        stateBedInfo[id] = [:]
      }
      List<String> components = []
      for (Map component in (List<Map>)bed.components) {
        if ((String) component[sTYP] == 'Base'
                && ((String) component.model).toLowerCase().contains('integrated')) {
          // Integrated bases need to be treated separately as they don't appear to have
          // foundation status endpoints so don't lump this with a base type directly.
          components << 'Integrated Base'
        } else {
          components << (String)component[sTYP]
        }
      }
      stateBedInfo[id].components = components
      // Store the type as well as a boolean indicating if it's old or new
      stateBedInfo[id].bedType = bed[sGEN]
      // Only know one 'generation' that is the new API for now
      stateBedInfo[id].newApi = bed[sGEN] == 'fuzion'
      // Store the account id which is needed for bamkey API
      stateBedInfo[id].accountId = bed[sACCT_ID]

    }
  }
  if (!stateBedInfo) {
    warn 'No bed state set up'
  }
  setState('bedInfo', stateBedInfo)
}

/**
 * Gets all bed child devices even if they're in a virtual container.
 * Will not return the virtual container(s) or children of a parent
 * device
 */
List<ChildDeviceWrapper> getBedDevices() {
  List<ChildDeviceWrapper> children = []
  // If any child is a virtual container, iterate that too
  for (ChildDeviceWrapper child in (List<ChildDeviceWrapper>)getChildDevices()) {
    if ((Boolean)child.hasAttribute('containerSize')) {
      children.addAll((List)child.childList())
    } else {
      children.add(child)
    }
  }
  return children
}

/**
 * Returns a list of maps of all bed devices, even those that are child devices.
 * The map keys are: name, type, side, deviceId, bedId, isChild
 */
List<Map> getBedDeviceData() {
  // Start with all bed devices.
  List<ChildDeviceWrapper> devices = getBedDevices()
  List<Map> output = []
  for (ChildDeviceWrapper device in devices) {
    String side = getBedDeviceSide(device)
    String bedId = getBedDeviceId(device)
    String type = getBedDeviceType(device) ?: 'Parent'

    output << [
      (sNM): (String) device.label,
      (sTYP): type,
      (sSIDE): side,
      (sDEVICEID): device.id,
      (sBEDID): bedId,
      isChild: false,
    ]
    for (ChildDeviceWrapper child in device.getChildDevices()) {
      output << [
        (sNM): (String)child.label,
        (sTYP): (String)device.getChildType(child.deviceNetworkId),
        (sSIDE): side,
        (sDEVICEID): child.id,
        (sBEDID): bedId,
        isChild: true,
      ]
    }
  }
  return output
}

/**
 * Returns devices types for a supplied bedId as a unique set of values.
 * Given there is no need to map specific sides to a device type, keeping this simple for
 * now, but associating at least to the supplied bedID. The only consumer of this function
 * already iterates by bed.
 */
Set<String> getBedDeviceTypes(String bedId) {
  List<Map> data = getBedDeviceData()
  Set<String> typeList; typeList = data.collect { if ((String) it.bedId == bedId) { return (String) it.type } }

  // cull NULL entries
  typeList = typeList.findAll()
  return typeList
}

// Use with #schedule as apparently it's not good to mix #runIn method call
// and #schedule method call to the same method.
void scheduledRefreshChildDevices() {
  remTsVal(sLASTFAMILYDATA)
  outletMapFLD = [:]
  refreshChildDevices()
  if (getSettingB('variableRefresh')) {
    // If we're using variable refresh then try to reconfigure it since bed states
    // have been updated and we may be in daytime.
    configureVariableRefreshInterval()
  }
}

void refreshChildDevices() {
  // Only refresh if mode is a selected one
  List setModes = (List) gtSetting('modes')
  if (setModes && !setModes.contains(location.mode)) {
    debug 'Skipping refresh, not the right mode'
    return
  }
  // If there's a switch defined and it's on, don't bother refreshing at all
  def disableSwitch = gtSetting('switchToDisable')
  if (disableSwitch && (String) disableSwitch.currentValue(sSWITCH) == sON) {
    debug 'Skipping refresh, switch to disable is on'
    return
  }

  wrunIn(4L, 'doRefresh')
}

void doRefresh() {
  debug 'Refresh child devices'
  getBedData(true)
}

/**
 * Called by driver when user triggers poll.
 */
void refreshChildDevices(Map ignored, String ignoredDevId) {
  Integer lastUpd = getLastTsValSecs(sLASTFAMILYDATA)
  if (lastUpd > 40) {
    remTsVal(sLASTFAMILYDATA)
    outletMapFLD = [:]
  }
  refreshChildDevices()
}

/**
 * Sets the refresh interval or resets to the app setting value if
 * 0 is given.
 * Can be used when refresh interval is long (say 15 or 30 minutes) during the day
 * but quicker, say 1 minute, is desired when presence is first detected or it's
 * a particular time of day.
 */
void setRefreshInterval(BigDecimal val, String ignoredDevId) {
  debug ('setRefreshInterval(%s)', val)
  Random random = new Random()
  Integer randomSec = random.nextInt(40) + i4
  if (val && val.toInteger() > iZ) {
    Integer randomMin = random.nextInt(Math.min(60-val.toInteger(), val.toInteger()))
    schedule("${randomSec} ${randomMin}/${val.toInteger()} * * * ?", 'scheduledRefreshChildDevices')
  } else {
    if (!getSettingB('variableRefresh')) {
      Integer interval = getSettingI('refreshInterval') ?: i1
      Integer randomMin = random.nextInt(Math.min(60-interval, interval))
      debug('Resetting interval to %s', interval)
      schedule("${randomSec} ${randomMin}/${interval} * * * ?", 'scheduledRefreshChildDevices')
    } else {
      configureVariableRefreshInterval()
    }
  }
}

/**
 * Configures a variable refresh interval schedule so that polling happens slowly
 * during the day but at night can poll quicker in order to detect things like presence
 * faster.  Daytime will be used if the time is between day and night _and_ no presence
 * is detected.
 * If user opted to use modes, this just checks the mode and sets the appropriate polling
 * based on that.
 */
void configureVariableRefreshInterval(evt) {
  configureVariableRefreshInterval()
}

void configureVariableRefreshInterval() {
  Boolean night
  if (getSettingB('variableRefreshModes')) {
    night = ((List) gtSetting('nightMode')).contains(location.mode)
  } else {
    // Gather presence == present child devices
    List<ChildDeviceWrapper> presentChildren = getBedDevices().findAll { ChildDeviceWrapper it ->
      String t = getBedDeviceType(it)
      (!t || t == sPRESENCE) && (Boolean) it.isPresent()
    }
    Date now = new Date()
    if (wtimeOfDayIsBetween(wtoDateTime(getSettingStr('dayStart')), wtoDateTime(getSettingStr('nightStart')), now)) {
      if (presentChildren.size() > iZ) return // if someone is still in bed, don't change anything
      night = false
    } else {
      night = true
    }
  }

  String s; s = sNL
  Integer ival; ival = null
  String varRefresh = (String) getState('variableRefresh')
  if (night) {
    // Don't bother setting the schedule if we are already set to night.
    if (varRefresh != 'night') {
      ival = getSettingI('nightInterval')
      s = 'night'
    }
  } else if (varRefresh != 'day') {
    ival = getSettingI('dayInterval')
    s = 'day'
  }
  if (s) {
    Random random = new Random()
    Integer randomSec = random.nextInt(40) + i4
    Integer randomMin = random.nextInt(Math.min(60-ival, ival))
    info ('Setting interval to %s. Refreshing every %s minutes.', s, ival)
    schedule("${randomSec} ${randomMin}/${ival} * * * ?", 'scheduledRefreshChildDevices')
    setState('variableRefresh',s)
  }
}

Map findBedPage() {
  Map responseData = getBedData()
  List<ChildDeviceWrapper> devices = getBedDevices()
  List childDevices = []
  List<Map> beds = responseData ? (List<Map>)responseData.beds : []
  dynamicPage(name: 'findBedPage') {
    if (beds.size() > iZ) {
      String l = sLEFT
      String r = sRIGHT
      for (Map bed in beds) {
        List sidesSeen = []
        String bdId = bed[sBEDID].toString()
        section("Bed: ${bdId}") {
          paragraph '<br>Note: <i>Sides are labeled as if you are laying in bed.</i>'
          if (devices.size() > iZ) {
            for (ChildDeviceWrapper dev in devices) {
              if (getBedDeviceId(dev) != bdId) {
                debug "bedId's don't match, skipping"
                continue
              }
              String dt = getBedDeviceType(dev)
              String ds = getBedDeviceSide(dev)
              if (!dt || dt == 'presence') {
                childDevices << ds
                sidesSeen << ds
                addBedSelectLink(ds, bdId, (String)dev.label, 'modify')
              }
            }
            if (childDevices.size() < i2) {
              input 'createNewChildDevices', sBOOL, (sTIT): 'Create new child device types', defaultValue: false, submitOnChange: true
              if (getSettingB('createNewChildDevices')) {
                if (!childDevices.contains(l)) {
                  addBedSelectLink(l, bdId)
                }
                if (!childDevices.contains(r)) {
                  addBedSelectLink(r, bdId)
                }
              }
            } else {
              app.removeSetting('createNewChildDevices')
            }
          }
          if (!sidesSeen.contains(l)) {
            addBedSelectLink(l, bdId)
          }
          if (!sidesSeen.contains(r)) {
            addBedSelectLink(r, bdId)
          }
        }
      }
    } else {
      section {
        paragraph 'No Beds Found'
      }
    }
  }
}

void addBedSelectLink(String side, String bedId, String label = sNL, String modifyCreate = 'create') {
  href 'selectBedPage', (sNM): "Bed: ${bedId}", (sTIT): label ?: "${side} Side", (sDESC): "Click to ${modifyCreate}",
          params: [(sBEDID): bedId, (sSIDE): side, label: label]
}

static String presenceText(presence) {
  return presence ? 'Present' : 'Not Present'
}

void checkBedInfo() {
  Integer lastUpd = getLastTsValSecs(sLASTBEDDATA)
  if (lastUpd > 600) {
    remTsVal(sLASTBEDDATA)
    remTsVal(sLASTFAMILYDATA)
    initializeBedInfo()
  }
}

/*
 * rest calls
 *     getBeds() (C)
 *     getOutletState(bedId, *) * 4 (C) (eventually if we initializedBedInfo)
 */
Map selectBedPage(Map iparams) {
  Map params; params = iparams
  if (params) {
    state.selectBedP = params
  } else {
    params = state.selectBedP
  }
  checkBedInfo()
  dynamicPage((sNM): 'selectBedPage') {
    String bdId = params?.bedId
    if (!bdId) {
      section {
        href 'homePage', (sTIT): 'Home', (sDESC): sNL
      }
      return
    }
    String side; side = (String)params[sSIDE]
    section {
      paragraph """<b>Instructions</b>
Enter a name, then choose whether or not to use child devices or a virtual container for the devices and then choose the types of devices to create.
Note that if using child devices, the parent device will contain all the special commands along with bed specific status while the children are simple
switches or dimmers.  Otherwise, all devices are the same on Hubitat, the only difference is how they behave to dim and on/off commands.  This is so that they may be used with external assistants such as Google Assistant or Amazon Alexa.  If you don't care about such use cases (and only want RM control or just presence), you can just use the presence type.
<br>
See <a href="https://community.hubitat.com/t/release-virtual-container-driver/4440" target = _blank>this post</a> for virtual container.
"""
        paragraph """<b>Device information</b>
Bed ID: ${bdId}
Side: ${side}
""" 
    }
    Long tbedId = Math.abs(Long.valueOf((String) params[sBEDID]))
    String varName = "${tbedId}.${side}".toString()
    String newName; newName = getSettingStr(varName)
    Boolean ucd; ucd = false
    section {
      String label = (String) params.label
      String name; name = newName
      name = !(name == 'null' || name == sNL) ? name : label
      name = !(name == 'null' || name == sNL) ? name : side
      input varName, sTXT, (sTIT): 'Device Name', defaultValue: name,
          (sDESC): 'What prefix do you want for the devices?', submitOnChange: true,
          required: true
      newName = getSettingStr(varName)
      input 'useChildDevices', sBOOL, (sTIT): 'Use child devices? (only recommended if  you have underbed lights or bed outlets)', defaultValue: true,
         submitOnChange: true
      ucd = getSettingB('useChildDevices')
      if (!ucd) {
        input 'useContainer', sBOOL, (sTIT): 'Use virtual container?', defaultValue: false,
           submitOnChange: true
      }
      side = side.toLowerCase()
      paragraph 'A presence type device exposes on/off as switching to a preset level (on) and flat (off).  Dimming will change the Sleep Number.'
      if (ucd) {
        paragraph 'This is the parent device when child devices are used'
        app.updateSetting 'createPresence', [(sVL): 'true', (sTYP): sBOOL]
        settings.createPresence = true
      } else {
        input 'createPresence', sBOOL,
            (sTIT): "Create presence device for ${side} side?",
            defaultValue: true, submitOnChange: true
      }
      paragraph 'A head type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the head position (0 is flat, 100 is fully raised).'
      input 'createHeadControl', sBOOL,
         (sTIT): "Create device to control the head of the ${side} side?",
         defaultValue: true, submitOnChange: true
      paragraph 'A foot type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the foot position (0 is flat, 100 is fully raised).'
      input 'createFootControl', sBOOL,
         (sTIT): "Create device to control the foot of the ${side} side?",
         defaultValue: true, submitOnChange: true
      if (((List<String>) ((Map) state.bedInfo[bdId]).components).contains('Warming')) {
        paragraph 'A foot type device exposes on/off as switching the foot warming on or off.  Dimming will change the heat levels (1: low, 2: medium, 3: high).'
        input 'createFootWarmer', sBOOL,
           (sTIT): "Create device to control the foot warmer of the ${side} side?",
           defaultValue: true, submitOnChange: true
      }
      determineUnderbedLightSetup(bdId)
      determineOutletSetup(bdId)
      if (ucd) {
        if (((List) ((Map)state.bedInfo[bdId]).underbedoutlets).size() > iZ) {
          paragraph 'Underbed lighting creates a dimmer allowing the light to be turned on or off at different levels with timer based on parent device preference.'
          input 'createUnderbedLighting', sBOOL,
                  (sTIT): "Create device to control the underbed lighting of the ${side} side?",
                  defaultValue: false, submitOnChange: true
        }
        if (((List) ((Map)state.bedInfo[bdId]).outlets).size() > iZ) {
          paragraph 'Outlet creates a switch allowing foundation outlet for this side to be turned on or off.'
          input 'createOutlet', sBOOL,
           (sTIT): "Create device to control the outlet of the ${side} side?",
             defaultValue: false, submitOnChange: true
        }
      }
    }
    if (!ucd || !getSettingB('createUnderbedLighting')) app.removeSetting('createUnderbedLighting')
    if (!ucd || !getSettingB('createOutlet')) app.removeSetting('createOutlet')

    section {
      StringBuilder msg; msg = new StringBuilder('Will create the following devices')
      String containerName; containerName = sBLK
      List<String> types = []
      if (ucd) {
        app.updateSetting 'useContainer', [(sVL): 'false', (sTYP): sBOOL]
        settings.useContainer = false
        msg.append(' with each side as a primary device and each type as a child device of the side')
      } else if (getSettingB('useContainer')) {
        containerName = "${newName} Container"
        msg.append(" in virtual container '").append(containerName).append("'")
      }
      msg.append(':<ol>')
      if (getSettingB('createPresence')) {
        msg.append('<li>').append(createDeviceLabel(newName, sPRESENCE)).append('</li>')
        types.add(sPRESENCE)
      }
      if (getSettingB('createHeadControl')) {
        msg.append('<li>').append(createDeviceLabel(newName, sHEAD)).append('</li>')
        types.add(sHEAD)
      }
      if (getSettingB('createFootControl')) {
        msg.append('<li>').append(createDeviceLabel(newName, sFOOT)).append('</li>')
        types.add(sFOOT)
      }
      if (getSettingB('createFootWarmer')) {
        msg.append('<li>').append(createDeviceLabel(newName, sFOOTWMR)).append('</li>')
        types.add(sFOOTWMR)
      }
      if (getSettingB('createUnderbedLighting') && ucd) {
        msg.append('<li>').append(createDeviceLabel(newName, sUNDERBEDLIGHT)).append('</li>')
        types.add(sUNDERBEDLIGHT)
      }
      if (getSettingB('createOutlet') && ucd) {
        msg.append('<li>').append(createDeviceLabel(newName, sOUTLET)).append('</li>')
        types.add(sOUTLET)
      }
      msg.append('</ol>')
      paragraph msg.toString()
      paragraph '<b>Click create below to continue</b>'
      href 'createBedPage', (sTIT): 'Create Devices', (sDESC): sNL,
      params: [
        presence: params.present,
        (sBEDID): bdId,
        (sSIDE): params[sSIDE],
        useChildDevices: ucd,
        useContainer: getSettingB('useContainer'),
        containerName: containerName,
        types: types
      ]
    }
  }
}

static String createDeviceLabel(String name, String type) {
  switch (type) {
    case sPRESENCE:
      return name
    case sHEAD:
      return name + ' Head'
    case sFOOT:
      return name + ' Foot'
    case sFOOTWMR:
      return name + ' Foot Warmer'
    case sUNDERBEDLIGHT:
      return name + ' Underbed Light'
    case sOUTLET:
      return name + ' Outlet'
    default:
      return name + ' Unknown'
  }
}

Map createBedPage(Map iparams) {
  Map params; params = iparams
  if (params) state.createBedP = params else params = state.createBedP
  ChildDeviceWrapper container; container = null
  if ((Boolean) params.useContainer) {
    container = createContainer((String) params[sBEDID], (String) params.containerName, (String) params[sSIDE])
  }
  List<ChildDeviceWrapper> existingDevices = getBedDevices()
  List<ChildDeviceWrapper> devices = []
  // TODO: Consider allowing more than one identical device for debug purposes.

  Long bedId = Math.abs(Long.valueOf((String) params[sBEDID]))
  String varName = "${bedId}.${params[sSIDE]}".toString()
  String newName; newName = getSettingStr(varName)

  if ((Boolean) params.useChildDevices) {
    // Bed Ids seem to always be negative so convert to positive for the device
    // id for better formatting.
    String deviceId = "sleepnumber.${bedId}.${params[sSIDE]}".toString() + (IS_BETA ? 'beta' : sBLK)
    String label = createDeviceLabel(newName, sPRESENCE)
    ChildDeviceWrapper parent; parent = existingDevices.find{ (String) it.deviceNetworkId == deviceId }
    if (parent) {
      info('Parent device %s already exists', deviceId)
    } else {
      debug('Creating parent device %s', deviceId)
      parent = addChildDevice(NAMESPACE, DRIVER_NAME, deviceId, null, [label: label])
      parent.setStatus(params.presence)
      parent.setBedId(params[sBEDID])
      parent.setSide(params[sSIDE])
      devices.add(parent)
    }
    // If we are using child devices then we create a presence device and
    // all others are children of it.
    for (String type in (List<String>)params.types) {
      if (type != sPRESENCE) {
        String childId = deviceId + '-' + type.replaceAll(sSPACE, sBLK)
        String driverType; driverType = sNL
        //noinspection GroovyFallthrough
        switch (type) {
          case sOUTLET:
            driverType = 'Switch'
            break
          case sHEAD:
          case sFOOT:
          case sFOOTWMR:
          case sUNDERBEDLIGHT:
            driverType = 'Dimmer'
        }
        ChildDeviceWrapper newDevice = parent.createChildDevice(childId, "Generic Component ${driverType}",
          createDeviceLabel(newName, type))
        if (newDevice) {
          devices.add(newDevice)
        }
      }
    }
  } else {
    for (String type in (List<String>)params.types) {
      String deviceId = "sleepnumber.${params[sBEDID]}.${params[sSIDE]}.${type.replaceAll(' ', '_')}".toString()
      if (existingDevices.find{ it.data.vcId == deviceId }) {
        info('Not creating device %s, it already exists', deviceId)
      } else {
        String label = createDeviceLabel(newName, type)
        ChildDeviceWrapper device
        if (container) {
          debug('Creating new child device %s with label %s in container %s', deviceId, label, params.containerName)
          container.appCreateDevice(label, DRIVER_NAME, NAMESPACE, deviceId)
          // #appCreateDevice doesn't return the device so find it
          device = container.childList().find({it.data.vcId == deviceId})
        } else {
          device = addChildDevice(NAMESPACE, DRIVER_NAME, deviceId, null, [label: label])
        }
        device.setStatus(params.presence)
        device.setBedId(params[sBEDID])
        device.setSide(params[sSIDE])
        device.setType(type)
        devices.add(device)
      }
    }
  }
  // Reset the bed info since we added more.
  checkBedInfo()
  dynamicPage((sNM): 'selectDevicePage') {
    section {
      StringBuilder header; header = new StringBuilder('Created new devices')
      if ((Boolean) params.useChildDevices) {
        header.append(' using child devices')
      } else if ((Boolean) params.useContainer) {
        header.append(' in container ').append(params.containerName)
      }
      header.append(':')
      paragraph(header.toString())
      StringBuilder displayInfo; displayInfo = new StringBuilder('<ol>')
      for (ChildDeviceWrapper device in devices) {
        displayInfo.append('<li>')
        displayInfo.append((String)device.label)
        if (!(Boolean) params.useChildDevices) {
          displayInfo.append('<br>Bed ID: ').append(getBedDeviceId(device))
          displayInfo.append('<br>Side: ').append(getBedDeviceSide(device))
          displayInfo.append('<br>Type: ').append(getBedDeviceType(device))
        }
        displayInfo.append('</li>')
      }
      displayInfo.append('</ol>')
      paragraph displayInfo.toString()
    }
    section {
      href 'findBedPage', (sTIT): 'Back to Bed List', (sDESC): sNL
    }
  }
}

/**
 * rest calls
 *    getBeds() (C)
 */
Map diagnosticsPage(Map iparams) {
  Map params; params = iparams
  if (params) setState('diagP', params) else params = (Map) getState('diagP')
  Map bedInfo = getBeds(true)
  dynamicPage((sNM): 'diagnosticsPage') {
    for (Map bed in (List<Map>)bedInfo.beds) {
      section("Bed: ${bed[sBEDID]}") {
        StringBuilder bedOutput; bedOutput = new StringBuilder('<ul>')
        bedOutput.append('<li>Size: ').append(bed.size)
        bedOutput.append('<li>Dual Sleep: ').append(bed.dualSleep)
        bedOutput.append('<li>Components:')
        for (Map component in (List<Map>)bed.components) {
          bedOutput.append('<ul>')
          bedOutput.append('<li>Type: ').append(component[sTYP])
          bedOutput.append('<li>Status: ').append(component.status)
          bedOutput.append('<li>Model: ').append(component.model)
          bedOutput.append('</ul>')
        }
        paragraph bedOutput.toString()
      }
    }
    //section('Dump of bedInfo') {
     // paragraph getMapDescStr(bedInfo)
    //}
    section('Dump of http request counts') {
      paragraph getMapDescStr(httpCntsMapFLD)
    }
    section('Send Requests') {
      input('requestType', sENUM, (sTIT): 'Request type', options: ['PUT', 'GET'])
      input('requestPath', sTXT, (sTIT): 'Request path', (sDESC): 'Full path including bed id if needed')
      input('requestBody', sTXT, (sTIT): 'Request Body in JSON')
      input('requestQuery', sTXT, (sTIT): 'Extra query key/value pairs in JSON')
      href('diagnosticsPage', (sTIT): 'Send request', (sDESC): sNL, params: [
              requestType : getSettingStr('requestType'),
              requestPath : getSettingStr('requestPath'),
              requestBody : getSettingStr('requestBody'),
              requestQuery: getSettingStr('requestQuery')
      ])
      if (params && params.requestPath && params.requestType) {
        Map body; body = null
        if (params.requestBody) {
          try {
            body = (Map) wparseJson((String) params.requestBody)
          } catch (e) {
            maybeLogError('%s : %s', params.requestBody, e)
          }
        }
        Map query; query = null
        if (params.requestQuery) {
          try {
            query = (Map) wparseJson((String) params.requestQuery)
          } catch (e) {
            maybeLogError('%s : %s', params.requestQuery, e)
          }
        }
        Map response = httpRequest((String) params.requestPath,
                (String) params.requestType == 'PUT' ? this.&put : this.&get,
                body,
                query,
                true)
        paragraph getMapDescStr(response)
      }
    }
    section('Authentication') {
      href 'diagnosticsPage', title: 'Clear session info', description: null, params: [clearSession: true]
      if (params && (Boolean) params.clearSession) {
        state.session = null
      }
    }
  }
}

/**
 * Creates a virtual container with the given name and side
 */
ChildDeviceWrapper createContainer(String bedId, String containerName, String side) {
  ChildDeviceWrapper container
  container = ((List<ChildDeviceWrapper>) getChildDevices()).find{ (String) it.typeName == 'Virtual Container' &&  (String) it.label == containerName}
  if (!container) {
    debug('Creating container %s', containerName)
    try {
      container = addChildDevice('stephack', 'Virtual Container', "${app.id}.${bedId}.${side}", null,
          [(sNM): containerName, label: containerName, completedSetup: true])
    } catch (e) {
      logError('Container device creation failed with error = ', e)
      return null
    }
  }
  return container
}

@CompileStatic
Map getBedData(Boolean async = false) {
  Boolean lazy = async

  String myId = gtAid()
  Integer lastUpd = getLastTsValSecs(sLASTFAMILYDATA)
  if (familyMapFLD[myId] && ((!lazy && lastUpd < 180) || (lazy && lastUpd <= 550))) {
    debug "Getting CACHED family status ${ devdbg() ? familyMapFLD[myId] : sBLK}"
    addHttpR('/rest/bed/familyStatus' + sCACHE)
    processBedData(familyMapFLD[myId])
    return familyMapFLD[myId]
  }

  if (!async) {
    Map responseData = getFamilyStatus()
    processBedData(responseData)
    return responseData
  }
  getAsyncFamilyStatus()
  return null
}

/**
 * Updates the bed devices with the given data.
 * This may make several rest calls depending on devices found
 *     getPrivacyMode(bedId, true) (C)
 *     getFoundationStatus(bedId, bedSideStr)
 *     getFootWarmingStatus(bedId)
 *     getOutletState(bedId, *) * 2 (C)
 *     getUnderbedLightState(bedId)
 *     getUnderbedLightBrightness(bedId)
 *         getFoundationSystem(bedId) (C)
 *     getSleepNumberFavorite(bedId) (C)
 *     getResponsiveAirStatus(bedId)
 */
void processBedData(Map responseData) {
  if (!responseData || responseData.size() == iZ) {
    debug 'Empty response data'
    return
  }
  //debug("Response data from SleepNumber: %s", responseData)
  // cache for foundation status per bed id so we don't have to run the api call N times
  Map<String, Object> foundationStatus = [:]
  Map<String, Object> footwarmingStatus = [:]
  Map<String, String> privacyStatus = [:]
  Map<String, Boolean> bedFailures = [:]
  Map<String, Boolean> loggedError = [:]
  Map<String, Object> sleepNumberFavorites = [:]
  Map<String, List> outletData = [:]
  Map<String, Map> underbedLightData = [:]
  Map<String, Object> responsiveAir = [:]

  for (ChildDeviceWrapper device in getBedDevices()) {
    String bedId = getBedDeviceId(device)
    String bedSideStr = getBedDeviceSide(device)
    if (devdbg()) debug("updating $device id: $bedId side: $bedSideStr")

    if (!outletData[bedId]) {
      outletData[bedId] = []
      underbedLightData[bedId] = [:]
    }

    Set<String> deviceTypes = getBedDeviceTypes(bedId)
    Map bedInfo; bedInfo = (Map) getState('bedInfo')

    for (Map bed in (List<Map>)responseData.beds) {
      String bedId1 = (String)bed[sBEDID]
      Map bedInfoBed; bedInfoBed = bedInfo ? (Map)bedInfo[bedId1] : null
      // Make sure the various bed state info is set up so we can use it later.
      if (!bedInfoBed || !bedInfoBed.components) {
        warn 'state.bedInfo somehow lost, re-caching it'
        initializeBedInfo()
        bedInfo = (Map) getState('bedInfo')
        bedInfoBed =  bedInfo ? (Map)bedInfo[bedId1] : null
      }
      if (bedId == bedId1) {
        debug("matched $device id: $bedId side: $bedSideStr")
        if (!bedFailures[bedId] && !privacyStatus[bedId]) {
          privacyStatus[bedId] = getPrivacyMode(bedId, true)
          if (!privacyStatus[bedId]) {
            bedFailures[bedId] = true
          }
        }
        // Note that it is possible to have a mattress without the base.  Prior, this used the presence of "Base"
        // in the bed status but it turns out SleepNumber doesn't always include that even when the base is
        // adjustable.  So instead, this relies on the devices the user created.
        if (!bedFailures[bedId]
            && !foundationStatus[bedId]
            && (deviceTypes.contains(sHEAD) || deviceTypes.contains(sFOOT))
          ) {
          foundationStatus[bedId] = getFoundationStatus(bedId)
          if (!foundationStatus[bedId]) {
            bedFailures[bedId] = true
          }
        }
        // So far, the presence of "Warming" in the bed status indicates a foot warmer.
        if (!bedFailures[bedId]
            && !footwarmingStatus[bedId]
            && ((List<String>)bedInfoBed?.components)?.contains('Warming')
            && (deviceTypes.contains(sFOOTWMR) || deviceTypes.contains('footwarmer'))
          ) {
          // Only try to update the warming state if the bed actually has it
          // and there's a device for it.
          footwarmingStatus[bedId] = getFootWarmingStatus(bedId)
          if (!footwarmingStatus[bedId]) {
            bedFailures[bedId] = true
          }
        }

        // If there's underbed lighting or outlets then poll for that data as well.  Don't poll
        // otherwise since it's just another network request and may be unwanted.
        // RIGHT_NIGHT_STAND = 1 LEFT_NIGHT_STAND = 2 RIGHT_NIGHT_LIGHT = 3 LEFT_NIGHT_LIGHT = 4
        if (!bedFailures[bedId] && deviceTypes.contains(sUNDERBEDLIGHT)) {
          determineUnderbedLightSetup(bedId)
          bedInfo = (Map)getState('bedInfo')
          bedInfoBed =  bedInfo ? (Map)bedInfo[bedId1] : null
          if (!outletData[bedId][i3]) {
            outletData[bedId][i3] = getOutletState(bedId, i3)
            if (!outletData[bedId][i3]) {
              bedFailures[bedId] = true
            }
          }
          if (!bedFailures[bedId] && !underbedLightData[bedId]) {
            underbedLightData[bedId] = getUnderbedLightState(bedId)
            if (!underbedLightData[bedId]) {
              bedFailures[bedId] = true
            } else {
              Map brightnessData = getUnderbedLightBrightness(bedId)
              if (!brightnessData) {
                bedFailures[bedId] = true
              } else {
                underbedLightData[bedId] << brightnessData
              }
            }
          }
          if (((List)bedInfoBed?.underbedoutlets)?.size() > i1) {
            if (!bedFailures[bedId] && !outletData[bedId][i4]) {
              outletData[bedId][i4] = getOutletState(bedId, i4)
              if (!outletData[bedId][i4]) {
                bedFailures[bedId] = true
              }
            }
          } else {
            outletData[bedId1][i4] = outletData[bedId1][i3]
          }
        }

        // RIGHT_NIGHT_STAND = 1 LEFT_NIGHT_STAND = 2 RIGHT_NIGHT_LIGHT = 3 LEFT_NIGHT_LIGHT = 4
        if (!bedFailures[bedId] && deviceTypes.contains(sOUTLET)) {
          determineOutletSetup(bedId)
          //bedInfo = (Map)gtSt('bedInfo')
          //bedInfoBed =  bedInfo ? (Map)bedInfo[bedId1] : null
          if (!outletData[bedId][i1]) {
            outletData[bedId][i1] = getOutletState(bedId, i1)
            if (!outletData[bedId][i1]) {
              bedFailures[bedId] = true
            } else {
              outletData[bedId][i2] = getOutletState(bedId, i2)
              if (!outletData[bedId][i2]) {
                bedFailures[bedId] = true
              }
            }
          }
        }

        Map<String, Object> bedSide = bedSideStr == sRIGHT ? (Map<String, Object>) bed.rightSide : (Map<String, Object>) bed.leftSide
        Map<String, Object> statusMap; statusMap = [
          presence: (Boolean)bedSide.isInBed,
          sleepNumber: (String)bedSide.sleepNumber,
          privacyMode: privacyStatus[bedId]
        ] as Map<String, Object>
        if (underbedLightData[bedId]) {
          Integer outletNumber = bedSideStr == sLEFT ? i3 : i4
          Map outletDataBedOut = outletData[bedId][outletNumber]
          String bstate = underbedLightData[bedId]?.enableAuto ? 'Auto' :
              outletDataBedOut?.setting == i1 ? sSTON : sSTOFF
          String timer = bstate == 'Auto' ? 'Not set' :
              outletDataBedOut?.timer ?: 'Forever'
          def brightness = underbedLightData[bedId]?."fs${bedSideStr}UnderbedLightPWM"
          statusMap << [
            underbedLightState: bstate,
            underbedLightTimer: timer,
            underbedLightBrightness: brightness,
          ]
        }

        if (outletData[bedId] && outletData[bedId][i1]) {
          Integer outletNumber = bedSideStr == sLEFT ? i1 : i2
          Map outletDataBedOut = outletData[bedId][outletNumber]
          statusMap << [
            outletState: outletDataBedOut?.setting == i1 ? sSTON : sSTOFF
          ]
        }
        // Check for valid foundation status and footwarming status data before trying to use it
        // as it's possible the HTTP calls failed.
        if (foundationStatus[bedId]) {
         statusMap << [
            headPosition: foundationStatus[bedId]["headPosition"],
            footPosition:  foundationStatus[bedId]["footPosition"],
            positionPreset: foundationStatus[bedId]["bedPreset"],
            positionPresetTimer: foundationStatus[bedId]["positionPresetTimer"],
            positionTimer: foundationStatus[bedId]["positionTimer"]
          ]
        } else if (!loggedError[bedId]) {
          //debug("Not updating foundation state, %s", (bedFailures.get(bedId) ? "error making requests" : "no data"))
        }
        if (footwarmingStatus[bedId]) {
          statusMap << [
            footWarmingTemp: footwarmingStatus[bedId]."footWarmingStatus${bedSideStr}",
            footWarmingTimer: footwarmingStatus[bedId]."footWarmingTimer${bedSideStr}",
          ]
        } else if (!loggedError[bedId]) {
          //debug("Not updating footwarming state, %s", (bedFailures.get(bedId) ? "error making requests" : "no data"))
        }
        if (!sleepNumberFavorites[bedId]) {
          sleepNumberFavorites[bedId] = getSleepNumberFavorite(bedId, true)
        }
	   // For now account for the fact that favorite may not be present (due to new API)
        Integer favorite = sleepNumberFavorites[bedId] ? ((Map)sleepNumberFavorites[bedId]).get("sleepNumberFavorite" + bedSideStr, -1) : 0
        if (favorite >= iZ) {
          statusMap << [
            sleepNumberFavorite: favorite
          ]
        }
        //determineResponsiveAirSetup(bedId)
        // If the device has responsive air, fetch that status and add to the map
        if (!bedFailures.get(bedId) && device.getSetting('enableResponsiveAir')) {
          if (!responsiveAir.get(bedId)) {
            responsiveAir[bedId] = getResponsiveAirStatus(bedId)
          }
          String side = bedSideStr.toLowerCase()
          statusMap << [
            responsiveAir: responsiveAir.get(bedId)?."${side}SideEnabled" ?: sBLK
          ]
        }
        if (bedFailures[bedId]) {
          // Only log update errors once per bed
          loggedError[bedId] = true
        }
        device.setStatus(statusMap)
        debug('update device %s side: %s status %s', device, bedSideStr, statusMap)
        break
      }
    }
  }
  if (bedFailures.size() && state.status == 'Online') {
    state.status = 'Bed / device mismatch'
  }
  updateLabel()
  if (foundationStatus || footwarmingStatus) {
    debug('Cached data: %s\n%s', foundationStatus, footwarmingStatus)
  }
}

@CompileStatic
Integer convertHexToNumber(String value) {
  if (value == sBLK || value == sNL) return iZ
  try {
    return Integer.parseInt(value, 16)
  } catch (Exception e) {
    error('Failed to convert non-numeric value %s', e)
    return iZ
  }
}

ChildDeviceWrapper findBedDevice(String deviceId) {
  ChildDeviceWrapper device = getBedDevices().find { ChildDeviceWrapper it -> deviceId == (String)it.deviceNetworkId }
  if (!device) {
    error('Bed device with id %s is not a valid child', deviceId)
    return null
  }
  return device
}

@Field volatile static Map<String, Map> sleepMapFLD = [:]

/**
 * get bed info with caching
 */
@CompileStatic
Map getBeds(Boolean lazy = false) {
  String myId = gtAid()
  Integer lastUpd = getLastTsValSecs(sLASTBEDDATA)
  if (sleepMapFLD[myId] && ((!lazy && lastUpd < 7200) || (lazy && lastUpd <= 14400))) {
    addHttpR('/rest/bed' + sCACHE)
    debug "Getting CACHED information for all beds ${ devdbg() ? sleepMapFLD[myId] : sBLK}"
    return sleepMapFLD[myId]
  }
  debug 'Getting information for all beds'
  Map res = httpRequest('/rest/bed')
  if (devdbg()) debug('Response data from SleepNumber: %s', res)
  if (res) {
    sleepMapFLD[myId] = res
    updTsVal(sLASTBEDDATA)
  }
  return res
}

@Field volatile static Map<String, Map> familyMapFLD = [:]

@CompileStatic
Map getFamilyStatus() {
  debug 'Getting family status'
  Map res = httpRequest('/rest/bed/familyStatus')
  if (devdbg()) debug('Response data from SleepNumber: %s', res)
  if (res) {
    String myId = gtAid()
    familyMapFLD[myId] = res
    updTsVal(sLASTFAMILYDATA)
  }
  return res
}

void getAsyncFamilyStatus(Boolean alreadyTriedRequest = false) {
  debug 'Getting family status async'
  Map sess = (Map) state.session
  Boolean useAwsO = getSettingB('useAwsOAuth')
  Boolean loginState = useAwsO ? !sess || !sess.accessToken : !sess || !sess.key
  if (loginState) {
    if (alreadyTriedRequest) {
      maybeLogError 'getAsyncFamilyStatus: Already attempted login but still no session key, giving up'
      return
    } else {
      login()
      getAsyncFamilyStatus(true)
      return
    }
  }
  String path = '/rest/bed/familyStatus'
  Map statusParams = fillParams(path, null, null, useAwsO, sess, true)
  addHttpR(path + ' async')
  try {
    wrunInMillis(24000L, 'timeoutFamily', [data: statusParams])
    asynchttpGet('finishGetAsyncFamilyStatus', statusParams, [:])
  } catch (e) {
    unschedule('timeoutFamily')
    String err = 'Error making family request %s\n%s'
    debug(err, statusParams, e)
    timeoutFamily()
  }
}

void finishGetAsyncFamilyStatus(resp, Map callbackData) {
  unschedule('timeoutFamily')
  Integer rCode; rCode = (Integer) resp.status
  if (resp.hasError()) {
    debug "retrying family async request as synchronous $rCode"
    getBedData()
    return
  }
  Map t0 = resp.getHeaders()
  String t1 = t0 != null ? (String) t0.'Content-Type' : sNL
  String mediaType; mediaType = t1 ? t1.toLowerCase()?.tokenize(';')[iZ] : sNL
  def data; data = resp.data
  Map ndata
  if (data != null && !(data instanceof Map) && !(data instanceof List)) {
    ndata = (Map) parseMyResp(data, mediaType)
  } else {
    ndata = data as Map
  }
  if (devdbg()) debug('Response data from SleepNumber: %s', ndata)
  if (ndata) {
    String myId = gtAid()
    familyMapFLD[myId] = ndata
    updTsVal(sLASTFAMILYDATA)
    processBedData(ndata)
  }
}

void timeoutFamily(Map request = null) {
  warn "family async request timeout $request"
  remTsVal(sLASTFAMILYDATA)
  getBedData()
}

@Field static final String sJSON = 'json'
@Field static final String sLB = '['
@Field static final String sRB = ']'
@Field static final String sOB = '{'
@Field static final String sCB = '}'

@CompileStatic
private static Boolean stJsonBracket(String c) { return c != sNL && c.startsWith(sOB) && c.endsWith(sCB) }

@CompileStatic
private static Boolean stJsonBrace(String c) { return c != sNL && c.startsWith(sLB) && c.endsWith(sRB) }

private parseMyResp(aa,String mediaType = sNL) {
  def ret
  ret = null
  if (aa instanceof String || aa instanceof GString) {
    String a = aa.toString() //.trim()
    Boolean expectJson = mediaType ? mediaType.contains(sJSON):false
    try {
      if (stJsonBracket(a)) {
        ret = (LinkedHashMap) new JsonSlurper().parseText(a)
      } else if (stJsonBrace(a)) {
        ret = (List) new JsonSlurper().parseText(a)
      } else if (expectJson || (mediaType in ['application/octet-stream'] && a.size() % i4 == iZ) ) { // HE can return data Base64
        String dec = new String(a.decodeBase64())
        if (dec != sNL) {
          def t0 = parseMyResp(dec, sBLK)
          ret = t0 == null ? dec : t0
        }
      }
    } catch (ignored) {}
  }
  return ret
}

Map<String, Map<String, Object>> getFoundationStatus(String bedId) {
  //  ACCP - args: <side> => returns time?  TODO: What did I mean by this??
  debug('Getting Foundation Status for %s', bedId)
  Map<String, Map<String, Object>> response = [:]
  if (isFuzion(bedId)) {
    if (!getState('systemConfiguration')) {
      debug("Getting system configuration to determine features")
      // call GetSystemConfiguration synchronously to get the list of supported features
      List<String> features = processBamKeyResponse(makeBamKeyHttpRequest(bedId, 'GetSystemConfiguration'))
      // Decompose features into just active ones and add that to state so we don't have to continue looking them up
      // as they shouldn't change
      List<String> activeFeatures = [FEATURE_NAMES, features].transpose().grep{ it[1] == "yes" }.collect({ it[0] as String })
      setState('systemConfiguration', activeFeatures)
    }
    // Actuators and presets
    [sRIGHT.toLowerCase(), sLEFT.toLowerCase()].each { side ->
      if (fuzionHasFeature("articulationEnableFlag")) {
        [sHEAD, sFOOT].each { actuator ->
          if (fuzionHasFeature("${side}${actuator.capitalize()}Actuator")) {
            response[side]["${actuator}Position"] = processBamKeyResponse(
                    makeBamKeyHttpRequest(bedId, 'GetActuatorPosition', [side, actuator]))[0]
          }
        }
      }
      response[side]["positionPreset"] = processBamKeyResponse(
              makeBamKeyHttpRequest(bedId, 'GetCurrentPreset', [side]))[0]
      // TODO - fuzion: asyncsleepiq doesn't seem to have the preset timer functionality so not sure what to use for that.
    }
  } else {
    Map status = httpRequest("/rest/bed/${bedId}/foundation/status")
    [sRIGHT, sLEFT].each { side ->
      // Positions are in hex so convert to a decimal
      if (status.containsKey("fs${side}HeadPosition")) response[side]["headPosition"] = convertHexToNumber((String) status["fs${side}HeadPosition"])
      if (status.containsKey("fs${side}FootPosition")) response[side]["footPosition"] = convertHexToNumber((String) status["fs${side}FootPosition"])
      if (status.containsKey("fsCurrentPositionPreset${side}")) response[side]["positionPreset"] = status["fsCurrentPositionPreset${side}"]
      // Time remaining to activate preset
      // There's also a MSB timer but not sure when that gets set.  Least significant bit seems used for all valid times.
      if (status.containsKey("fs${side}PositionTimerLSB")) response[side]["positionTimer"] = convertHexToNumber((String) status["fs${side}PositionTimerLSB"])
      // The preset that will be activated after timer expires
      if (status.containsKey("fsTimerPositionPreset${side}")) response[side]["positionPresetTimer"] = status["fsTimerPositionPreset${side}"]
    }
  }
  return response
}

Map getFootWarmingStatus(String bedId) {
  debug('Getting Foot Warming Status for %s', bedId)
  Map response = [:]
  if (isFuzion(bedId)) {
    // The new API doesn't have an overall status so we have to call for left and right and then build a response
    // like the old API
    // TODO: check features before making call - rapidSleepSettingEnableFlag
    List<String> values = processBamKeyResponse(makeBamKeyHttpRequest(bedId, 'GetFootWarming', ['left']))
    response['footWarmingStatusLeft'] = values[1] // the old API only had temp vs. an on/off value so just use that
    response['footWarmingTimerLeft'] = values[2]
    values = processBamKeyResponse(makeBamKeyHttpRequest(bedId, 'GetFootWarming', ['right']))
    response['footWarmingStatusRight'] = values[1]
    response['footWarmingTimerRight'] = values[2]
  } else {
    response = httpRequest("/rest/bed/${bedId}/foundation/footwarming")
  }
  return response
}

Map getResponsiveAirStatus(String bedId) {
  debug('Getting responsive air status for %s', bedId)
  Map response = [:]
  if (isFuzion(bedId)) {
    // New API is responsive air state per side w/ value of true or false so synthezise the old response
    List<String> values = processBamKeyResponse(makeBamKeyHttpRequest(bedId, 'GetResponsiveAirState', ['left']))
    response['leftSideEnabled'] = values[0].equals('1') ? 'true' : 'false'
    values = processBamKeyResponse(makeBamKeyHttpRequest(bedId, 'GetResponsiveAirState', ['right']))
    response['rightSideEnabled'] = values[0].equals('1') ? 'true' : 'false'
  } else {
    response = httpRequest("/rest/bed/${bedId}/responsiveAir")
  }
  return response
}

void setResponsiveAirState(Boolean st, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  String bedId = getBedDeviceId(device)
  String side = getBedDeviceSide(device).toLowerCase()
  debug('Setting responsive air state %s to %s', side, st)

  if (isFuzion(getBedDeviceId(device))) {
    addBamKeyRequestToQueue(bedId, 'SetResponsiveAirState', [side, st ? "1" : "0"], 0, sREFRESHCHILDDEVICES)
  } else {
    Map body = [:]
    if (side == 'right') {
      body << [
              rightSideEnabled: st
      ]
    } else {
      body << [
              leftSideEnabled: st
      ]
    }
    httpRequestQueue(0, path: "/rest/bed/${bedId}/responsiveAir",
            body: body, runAfter: sREFRESHCHILDDEVICES)
  }
}

/**
 * Params must be a Map containing keys actuator and position.
 * The side is derived from the specified device
 */
void setFoundationAdjustment(Map params, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  String actu = (String) params?.actuator
  Integer pos = (Integer) params?.position
  if (!actu || pos == null) {
    error('Missing param values, actuator and position are required')
    return
  }
  if (!VALID_ACTUATORS.keySet().contains(actu)) {
    error('Invalid actuator %s, valid values are %s', actu, VALID_ACTUATORS.keySet())
    return
  }
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // The timing appears to be linear which means it's 0.35 seconds per level adjusted for the head and 0.18
  // for the foot.
  Integer currentPosition = actu == 'H' ? device.currentValue('headPosition') : device.currentValue('footPosition')
  Integer positionDelta = Math.abs(pos - currentPosition)
  Float movementDuration = actu == 'H' ? 0.35 : 0.18
  Integer waitTime = Math.round(movementDuration * positionDelta).toInteger() + i1

  String bedId = getBedDeviceId(device)
  if (isFuzion(bedId)) {
    addBamKeyRequestToQueue(bedId, 'SetActuatorTargetPosition',
            [getBedDeviceSide(device)[iZ].toLowerCase(), VALID_ACTUATORS.get(actu).toLowerCase(), pos.toString()],
            waitTime, sREFRESHCHILDDEVICES)
  } else {
    Map body = [
            speed   : iZ, // 1 == slow, 0 = fast
            actuator: actu,
            (sSIDE) : getBedDeviceSide(device)[iZ],
            position: pos // 0-100
    ]
    httpRequestQueue(waitTime, path: "/rest/bed/${bedId}/foundation/adjustment/micro",
            body: body, runAfter: sREFRESHCHILDDEVICES)
  }
}

/**
 * Params must be a Map containing keys temp and timer.
 * The side is derived from the specified device
 */
void setFootWarmingState(Map params, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  Integer ptemp = (Integer) params?.temp
  Integer ptimer = (Integer) params?.timer
  if (ptemp == null || ptimer == null) {
    error('Missing param values, temp and timer are required')
    return
  }
  if (!VALID_WARMING_TIMES.contains(ptimer)) {
    error('Invalid warming time %s, valid values are %s', ptimer, VALID_WARMING_TIMES)
    return
  }
  if (!VALID_WARMING_TEMPS.keySet().contains(ptemp)) {
    error('Invalid warming temp %s, valid values are %s', ptemp, VALID_WARMING_TEMPS.keySet())
    return
  }
  String side = getBedDeviceSide(device)
  String bedId = getBedDeviceId(device)
  if (isFuzion(bedId)) {
    // TODO: Check feature flag (rapidSleepSettingEnableFlag)?
    addBamKeyRequestToQueue(bedId, 'SetFootWarming',
            [side.toLowerCase(), VALID_WARMING_TEMPS.get(ptemp), ptimer.toString()], 0, sREFRESHCHILDDEVICES)
  } else {
    Map body = [
            ("footWarmingTemp${side}".toString()) : ptemp,
            ("footWarmingTimer${side}".toString()): ptimer
    ]
    httpRequestQueue(0, path: "/rest/bed/${getBedDeviceId(device)}/foundation/footwarming",
            body: body, runAfter: sREFRESHCHILDDEVICES)
  }
}

/**
 * Params must be a map containing keys preset and timer.
 * The side is derived from the specified device
 */
void setFoundationTimer(Map params, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    error('Bed device with id %s is not a valid child', devId)
    return
  }
  Integer ppreset = (Integer) params?.preset
  Integer ptimer = (Integer) params?.timer
  if (ppreset == null || ptimer == null) {
    error('Missing param values, preset and timer are required')
    return
  }
  if (!VALID_PRESETS.keySet().contains(ppreset)) {
    error('Invalid preset %s, valid values are %s', ppreset, VALID_PRESETS.keySet())
    return
  }
  if (!VALID_PRESET_TIMES.contains(ptimer)) {
    error('Invalid timer %s, valid values are %s', ptimer, VALID_PRESET_TIMES)
    return
  }
  String bedId = getBedDeviceId(device)
  if (isFuzion(bedId)) {
    addBamKeyRequestToQueue(bedId, 'SetTargetPresetWithTimer',
            [getBedDeviceSide(device)[iZ].toLowerCase(), VALID_PRESETS.get(preset), ptimer.toString()],
            5, sREFRESHCHILDDEVICES)
  } else {
    Map body = [
            (sSIDE)       : getBedDeviceSide(device)[iZ],
            positionPreset: ppreset,
            positionTimer : ptimer
    ]
    httpRequestQueue(5, path: "/rest/bed/${bedId}/foundation/adjustment",
            body: body, runAfter: sREFRESHCHILDDEVICES)
  }
}

/**
 * The side is derived from the specified device
 */
void setFoundationPreset(Integer preset, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  if (!VALID_PRESETS.keySet().contains(preset)) {
    error('Invalid preset %s, valid values are %s', preset, VALID_PRESETS.keySet())
    return
  }
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // Rather than attempt to derive the preset relative to the current state so we can compute
  // the time (as we do for adjustment), we just use the maximum.
  Integer duration = 35
  String bedId = getBedDeviceId(device)
  if (isFuzion(bedId)) {
    addBamKeyRequestToQueue(bedId, 'SetTargetPresetWithoutTimer', [getBedDeviceSide(device)[iZ].toLowerCase(),
               VALID_PRESETS.get(preset)], duration, sREFRESHCHILDDEVICES)
  } else {
    Map body = [
            speed  : iZ,
            preset : preset,
            (sSIDE): getBedDeviceSide(device)[iZ]
    ]
    httpRequestQueue(duration, path: "/rest/bed/${bedId}/foundation/preset",
            body: body, runAfter: sREFRESHCHILDDEVICES)
  }
}

void stopFoundationMovement(Map ignored, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  remTsVal(sLASTFAMILYDATA)
  String bedId = getBedDeviceId(device)
  if (isFuzion(bedId)) {
    // TODO - Fuzion: asyncsleepiq doesn't use a side for this command but it sure seems like it should take one.  Test this.
    addBamKeyRequestToQueue(bedId, 'HaltAllActuators', [/*getBedDeviceSide(device)[iZ].toLowerCase()*/], 5, sREFRESHCHILDDEVICES)
   } else {
    Map body = [
            massageMotion: iZ,
            headMotion   : i1,
            footMotion   : i1,
            (sSIDE)      : getBedDeviceSide(device)[iZ]
    ]
    httpRequestQueue(5, path: "/rest/bed/${bedId}/foundation/motion",
            body: body, runAfter: sREFRESHCHILDDEVICES)
  }
}

// set sleep number to current favorite
void setSleepNumberFavorite(String ignored, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  // Get the favorite for the device first, the most recent poll should be accurate
  // enough.
  Integer favorite = device.currentValue('sleepNumberFavorite')
  String sid = getBedDeviceSide(device)
  debug "sleep number favorite for ${sid} is ${favorite}"
  if (!favorite || favorite < iZ) {
    error('Unable to determine sleep number favorite for side %s', sid)
    return
  }
  if (device.currentValue('sleepNumber') == favorite) {
    debug 'Already at favorite'
    return
  }
  setSleepNumber(favorite, devId)
}

// update the sleep number favorite
void updateSleepNumberFavorite(Integer number, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  // favorite setting 0-100 (rounds to nearest multiple of 5)
  Integer dfavorite = (Math.round(number / 5) * 5).toInteger()
  Integer favorite = device.currentValue('sleepNumberFavorite')
  String sid = getBedDeviceSide(device)
  debug "update sleep number favorite for ${sid} to ${dfavorite}, is ${favorite}"

  if (dfavorite && dfavorite > iZ && dfavorite <= 100) {
    if (dfavorite == favorite) {
      debug 'Already at favorite'
      return
    }
    String bedId = getBedDeviceId(device)
    if (isFuzion(bedId)) {
      addBamKeyRequestToQueue(bedId, 'SetFavoriteSleepNumber',
              [getBedDeviceSide(device)[iZ].toLowerCase(), dfavorite.toString()])
    } else {
      // side "R" or "L"
      Map body = [
              (sBEDID)           : bedId,
              sleepNumberFavorite: dfavorite,
              (sSIDE)            : sid[iZ]
      ]
      httpRequestQueue(0, path: "/rest/bed/${bedId}/sleepNumberFavorite", body: body)
    }
    remTsVal('lastSleepFavoriteUpdDt')
    setSleepNumber(dfavorite, devId)
  } else {
    logError "Unable to update sleep number favorite for side ${sid} ${number}"
  }
  remTsVal('lastSleepFavoriteUpdDt')
}

/**
 * The side is derived from the specified device
 */
void setSleepNumber(Integer number, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  String bedId = getBedDeviceId(device)
  // Not sure how long it takes to inflate or deflate so just wait 20s
  Integer duration = 20
  if (isFuzion(bedId)) {
    addBamKeyRequestToQueue(bedId, 'StartSleepNumberAdjustment', [getBedDeviceSide(device)[iZ], number.toString()],
            duration, sREFRESHCHILDDEVICES)
  } else {
    Map body = [
            (sBEDID)   : bedId,
            sleepNumber: number,
            (sSIDE)    : getBedDeviceSide(device)[iZ]
    ]
    httpRequestQueue(duration, path: "/rest/bed/${bedId}/sleepNumber",
            body: body, runAfter: sREFRESHCHILDDEVICES)
  }
}

@Field volatile static Map<String, Map> privacyMapFLD = [:]

/**
 * Privacy mode cached
 */
String getPrivacyMode(String bedId, Boolean lazy = false) {
  Integer lastUpd = getLastTsValSecs('lastPrivacyDataUpdDt')
  if (privacyMapFLD[bedId] && ((!lazy && lastUpd < 7200) || (lazy && lastUpd <= 14400))) {
    if (isFuzion(bedId)) {
      addHttpR(createBamKeyUrl(bedId, state.bedInfo[bedId].accountId) + " GetSleepiqPrivacyState" + sCACHE)
    } else {
      addHttpR("/rest/bed/${bedId}/pauseMode" + sCACHE)
    }
    debug "Getting CACHED Privacy Mode for ${bedId} ${ devdbg() ? privacyMapFLD[bedId] : sBLK}"
    return (String)privacyMapFLD[bedId].pauseMode
  }
  debug('Getting Privacy Mode for %s', bedId)
  Map res
  if (isFuzion(bedId)) {
    Boolean paused = processBamKeyResponse(
            makeBamKeyHttpRequest(bedId, 'GetSleepiqPrivacyState'))[0].equals('paused')
    res = ['pauseMode': paused ? 'paused' : 'active'] // what goes here?
  } else {
    res = httpRequest("/rest/bed/${bedId}/pauseMode")
  }
  if (devdbg()) debug('Response data from SleepNumber: %s', res)
  if (res) {
    privacyMapFLD[bedId] = res
    updTsVal('lastPrivacyDataUpdDt')
  }
  return (String) res?.pauseMode
}

void setPrivacyMode(Boolean mode, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  String bedId = getBedDeviceId(device)
  // Cloud request
  remTsVal('lastPrivacyDataUpdDt')
  remTsVal(sLASTFAMILYDATA)
  remTsVal('lastSleeperDataUpdDt')
  if (isFuzion(bedId)) {
    String pauseMode = mode ? sPAUSED : sACTIVE
    addBamKeyRequestToQueue(bedId, 'SetSleepiqPrivacyState', [pauseMode], 2, sREFRESHCHILDDEVICES)
  } else {
    String pauseMode = mode ? sON : sOFF
    httpRequestQueue(2, path: "/rest/bed/${bedId}/pauseMode",
            query: [mode: pauseMode], runAfter: sREFRESHCHILDDEVICES)
  }
}

@Field volatile static Map<String, Map> sleepNumMapFLD = [:]

Map getSleepNumberFavorite(String bedId, Boolean lazy = false) {
  if (isFuzion(bedId)) {
    warn "new API not supported yret"
    return [:]
  }	
  Integer lastUpd = getLastTsValSecs('lastSleepFavoriteUpdDt')
  if (sleepNumMapFLD[bedId] && ((!lazy && lastUpd < 7200) || (lazy && lastUpd <= 14400))) {
    addHttpR("/rest/bed/${bedId}/sleepNumberFavorite" + sCACHE)
    debug "Getting CACHED Sleep Number Favorites ${ devdbg() ? sleepNumMapFLD[bedId] : sBLK}"
    return sleepNumMapFLD[bedId]
  }
  debug 'Getting Sleep Number Favorites'
  Map res = httpRequest("/rest/bed/${bedId}/sleepNumberFavorite")
  if (devdbg()) debug('Response data from SleepNumber: %s', res)
  if (res) {
    sleepNumMapFLD[bedId] = res
    updTsVal('lastSleepFavoriteUpdDt')
  }
  return res
}

//RIGHT_NIGHT_STAND = 1
//LEFT_NIGHT_STAND = 2
//RIGHT_NIGHT_LIGHT = 3
//LEFT_NIGHT_LIGHT = 4
//
//BED_LIGHTS = [
//        RIGHT_NIGHT_STAND,
//        LEFT_NIGHT_STAND,
//        RIGHT_NIGHT_LIGHT,
//        LEFT_NIGHT_LIGHT
//    ]
//
//FAVORITE = 1
//READ = 2
//WATCH_TV = 3
//FLAT = 4
//ZERO_G = 5
//SNORE = 6
//
//BED_PRESETS = [
//        FAVORITE,
//        READ,
//        WATCH_TV,
//        FLAT,
//        ZERO_G,
//        SNORE
//    ]
//
//OFF = 0
//LOW = 1
//MEDIUM = 2
//HIGH = 3
//
//MASSAGE_SPEED = [
//        OFF,
//        LOW,
//        MEDIUM,
//        HIGH
//    ]
//
//SOOTHE = 1
//REVITILIZE = 2
//WAVE = 3
//
//MASSAGE_MODE = [
//        OFF,
//        SOOTHE,
//        REVITILIZE,
//        WAVE
//    ]

void setFoundationMassage(Integer ifootspeed, Integer iheadspeed, Integer itimer = iZ, Integer mode = iZ, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) return
  if (isFuzion(getBedDeviceId(device))) {
    // TODO - fuzion: Add this
    warn "new API not supported yet"
    return
  }

  Integer footspeed, headspeed
  footspeed = ifootspeed ?: iZ
  headspeed = iheadspeed ?: iZ
  Integer timer = itimer ?: iZ

  if (!(footspeed in VALID_SPEEDS && headspeed in VALID_SPEEDS)) {
    logError "Invalid speed ${footspeed} ${headspeed}.  Valid values are ${VALID_SPEEDS}"
    return
  }
  //  footSpeed 0-3
  //  headSpeed 0-3
  //  mode 0-3
  //  side "R" or "L"
  String side = getBedDeviceSide(device)
  String id = getBedDeviceId(device)
  Map body = [
    footMassageMotor: footspeed,
    headMassageMotor: headspeed,
    massageTimer: timer,
    massageWaveMode: mode,
    (sSIDE): side[iZ]
  ]
  httpRequestQueue(1, path: "/rest/bed/${id}/foundation/adjustment",
        body: body, runAfter: sREFRESHCHILDDEVICES)
}

@Field volatile static Map<String, Map> outletMapFLD = [:]

/**
 * get oulet state cached
 */
Map getOutletState(String bedId, Integer outlet) {
  if (isFuzion(bedId)) {
    warn "new API not supported yet"
    return [:]
  }	
  String val = 'lastOutletUpdDt' + outlet.toString()
  String idx = bedId+'_'+outlet.toString()
  Integer lastUpd = getLastTsValSecs(val)
  if (outletMapFLD[idx] && lastUpd <= 180) {
    addHttpR("/rest/bed/${bedId}/foundation/outlet " + outlet.toString() + sCACHE)
    debug "Getting CACHED outlet ${ devdbg() ? outletMapFLD[idx] : sBLK}"
    return outletMapFLD[idx]
  }
  debug "Getting Outlet data ${outlet}"
  Map res = httpRequest("/rest/bed/${bedId}/foundation/outlet",
        this.&get, null, [outletId: outlet])
  if (devdbg()) debug('Response data from SleepNumber: %s', res)
  if (res) {
    outletMapFLD[idx]=res
    updTsVal(val)
  }
  return res
}

void setOutletState(String outletState, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  if (!outletState) {
    error 'Missing outletState'
    return
  }
  Integer outletNum = getBedDeviceSide(device) == sLEFT ? i1 : i2
  setOutletState(getBedDeviceId(device), outletNum, outletState)
}

/**
 * Sets the state of the given outlet.
 * @param bedId: the bed id
 * @param outletId: 1-4
 * @param state: on or off
 * @param timer: optional indicating a valid minute duration (for outlets 3 and 4 only)
 * @param refresh: boolean indicating whether or not to skip refreshing state, refreshes by default
 */
void setOutletState(String bedId, Integer outletId, String ioutletState, Integer itimer = null,
                    Boolean refresh = true) {
  if (isFuzion(bedId)) {
    // TODO - fuzion: Add this
    warn "new API not supported yet"
    return
  }
  String outletState; outletState = ioutletState
  Integer timer; timer = itimer
  if (!bedId || !outletId || !outletState) {
    error 'Not all required arguments present'
    return
  }

  if (timer && !VALID_LIGHT_TIMES.contains(timer)) {
    error('Invalid underbed light timer %s.  Valid values are %s', timer, VALID_LIGHT_TIMES)
    return
  }

  outletState = (outletState ?: sBLK).toLowerCase()

  if (outletId < i3) {
    // No timer is valid for outlets other than 3 and 4
    timer = null
  } else {
    timer = timer ?: iZ
  }
  Map body = [
    timer: timer,
    setting: outletState == sON ? i1 : iZ,
    outletId: outletId
  ]
  String path = "/rest/bed/${bedId}/foundation/outlet"
  String val = 'lastOutletUpdDt' + outletId.toString()
  remTsVal(val)
    if (refresh) {
      httpRequestQueue(2, path: path, body: body, runAfter: sREFRESHCHILDDEVICES)
    } else {
      httpRequestQueue(0, path: path, body: body)
    }
}

@CompileStatic
Map getUnderbedLightState(String bedId) {
  httpRequest("/rest/bed/${bedId}/foundation/underbedLight", this.&get)
}

@Field volatile static Map<String, Map> foundationSystemMapFLD = [:]

@CompileStatic
Map getFoundationSystem(String bedId) {
  Integer lastUpd = getLastTsValSecs('lastFoundationSystemUpdDt')
  if (foundationSystemMapFLD[bedId] && lastUpd <= 14400) {
    addHttpR("/rest/bed/${bedId}/foundation/system" + sCACHE)
    debug "Getting CACHED Foundation System ${ devdbg() ? foundationSystemMapFLD[bedId] : sBLK}"
    return foundationSystemMapFLD[bedId]
  }
  debug 'Getting Foundation System'
  Map res = httpRequest("/rest/bed/${bedId}/foundation/system", this.&get)
  if (devdbg()) debug('Response data from SleepNumber: %s', res)
  if (res) {
    foundationSystemMapFLD[bedId]=res
    updTsVal('lastFoundationSystemUpdDt')
  }
  return res
}

/**
 * get underbed brigntness
 * rest calls
 *     calls getFoundationSystem (C)
 *     may call getOutletState * 2 (C)
 */
Map getUnderbedLightBrightness(String bedId) {
  if (isFuzion(bedId)) {
    warn "new API not supported yet"
    return [:]
  }
  determineUnderbedLightSetup(bedId)
  Map brightness = getFoundationSystem(bedId)
  if (brightness && ((List) ((Map) state.bedInfo[bedId]).underbedoutlets).size() == i1) {
    // Strangely if there's only one light then the `right` side is the set value
    // so just set them both the same.
    brightness.fsLeftUnderbedLightPWM = brightness.fsRightUnderbedLightPWM
  }
  return brightness
}

/**
 * Determines how many underbed lights exist and sets up state.
 * rest calls
 *     getOutletState(bedId, [3,4]) * 2 (C)
 *  @return   fills in state.bedInfo.underbedoutlets
 */
void determineUnderbedLightSetup(String bedId) {
  Map<String,Map> bdinfo = (Map<String,Map>) state.bedInfo
  if (bdinfo[bedId].underbedoutlets == null) {
    debug('Determining underbed lighting outlets for %s', bedId)
    // RIGHT_NIGHT_STAND = 1 LEFT_NIGHT_STAND = 2 RIGHT_NIGHT_LIGHT = 3 LEFT_NIGHT_LIGHT = 4
    // Determine if this bed has 1 or 2 underbed lighting outlets and store for future use.
    Map outlet3 = getOutletState(bedId, i3)
    Map outlet4 = getOutletState(bedId, i4)
    List outlets = []
    if (outlet3) {
      outlets << i3
    }
    if (outlet4) {
      outlets << i4
    }
    bdinfo[bedId].underbedoutlets = outlets
    state.bedInfo = bdinfo
  }
}

/**
 * Sets the underbed lighting per given params.
 * If only timer is given, state is assumed to be `on`.
 * If the foundation has outlet 3 and 4, the bed side will be used to enable.
 * The params map must include:
 *    state: on, off, auto
 * And may include:
 *    timer: valid minute duration
 *    brightness: low, medium, high
 */
void setUnderbedLightState(Map params, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  if (isFuzion(getBedDeviceId(device))) {
    // TODO - fuzion: Add this
    warn "new API not supported yet"
    return
  }

  if (!params.state) {
    error 'Missing param state'
    return
  }

  String ps; ps = ((String) params.state).toLowerCase()
  Integer pt,pb
  pt = (Integer) params.timer
  pb = (Integer) params.brightness

  // A timer with a state of auto makes no sense, choose to honor state vs. timer.
  if (ps == 'auto') {
    pt = iZ
  }
  if (pt) {
    ps = sON
  }

  if (pb && !VALID_LIGHT_BRIGHTNESS.contains(pb)) {
    error('Invalid underbed light brightness %s. Valid values are %s', pb, VALID_LIGHT_BRIGHTNESS)
    return
  }

  // First set the light state.
  Map body; body = [
    enableAuto: ps == 'auto'
  ]
  String id = getBedDeviceId(device)
  httpRequestQueue(2, path: "/rest/bed/${id}/foundation/underbedLight", body: body)

  determineUnderbedLightSetup(id)
  Integer rightBrightness, leftBrightness
  rightBrightness = pb
  leftBrightness = pb
  Integer outletNum; outletNum = i3
  if (((List) ((Map) state.bedInfo[id]).underbedoutlets).size() > i1) {
    // Two outlets so set the side corresponding to the device rather than
    // defaulting to 3 (which should be a single light)
    if (getBedDeviceSide(device) == sLEFT) {
      outletNum = i3
      rightBrightness = null
      leftBrightness = pb
    } else {
      outletNum = i4
      rightBrightness = pb
      leftBrightness = null
    }
  }
  // If brightness was given then set it.
  if (pb) {
    body = [
      rightUnderbedLightPWM: rightBrightness,
      leftUnderbedLightPWM: leftBrightness
    ]
    httpRequestQueue(2, path: "/rest/bed/${id}/foundation/system", body: body)
  }
  setOutletState(id, outletNum,
          ps == 'auto' ? sOFF : ps, pt, true)
}

/**
 * Determines how many outlets exist and sets up state.
 * rest calls
 *     getOutletState(bedId, [1,2]) * 2 (C)
 *  @return   fills in state.bedInfo.outlets
 */
void determineOutletSetup(String bedId) {
  Map<String,Map> bdinfo = (Map<String,Map>) state.bedInfo
  if (bdinfo[bedId].outlets == null) {
    debug('Determining outlets for %s', bedId)
    // RIGHT_NIGHT_STAND = 1 LEFT_NIGHT_STAND = 2 RIGHT_NIGHT_LIGHT = 3 LEFT_NIGHT_LIGHT = 4
    // Determine if this bed has 1 or 2 outlets and store for future use.
    Map outlet1 = getOutletState(bedId, i1)
    Map outlet2 = getOutletState(bedId, i2)
    List outlets = []
    if (outlet1) {
      outlets << i1
    }
    if (outlet2) {
      outlets << i2
    }
    bdinfo[bedId].outlets = outlets
    state.bedInfo = bdinfo
  }
}

@Field volatile static Map<String, Map> sleepersMapFLD = [:]

@CompileStatic
Map getSleepers(Boolean lazy = false) {
  Integer lastUpd = getLastTsValSecs('lastSleeperDataUpdDt')
  String myId = gtAid()
  String path = '/rest/sleeper'
  if (sleepersMapFLD[myId] && ((!lazy && lastUpd < 7200) || (lazy && lastUpd <= 14400))) {
    addHttpR(path + sCACHE)
    debug "Getting CACHED Sleepers ${ devdbg() ? sleepersMapFLD[myId] : sBLK}"
    return sleepersMapFLD[myId]
  }
  debug 'Getting Sleepers'
  Map res = httpRequest(path, this.&get)
  if (devdbg()) debug('Response data from SleepNumber: %s', res)
  if (res) {
    sleepersMapFLD[myId]=res
    updTsVal('lastSleeperDataUpdDt')
  }
  return res
}

/**
 *  called by child device to get summary sleep data
 *  rest calls
 *            getSleepers() (C)
 */
Map getSleepData(Map ignored, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return null
  }
  String bedId = getBedDeviceId(device)
  Map ids = [:]
  // We need a sleeper id for the side in order to look up sleep data.
  // Get sleeper to get list of sleeper ids
  Map sleepers = getSleepers(true)

  debug('Getting sleeper ids for %s', bedId)
  for (Map sleeper in (List<Map>)sleepers.sleepers) {
    if ((String)sleeper[sBEDID] == bedId) {
      String side; side = sNL
      switch (sleeper[sSIDE]) {
        case iZ:
          side = sLEFT
          break
        case i1:
          side = sRIGHT
          break
        default:
          warn('Unknown sleeper info: %s', sleeper)
      }
      if (side != sNL) {
        ids[side] = sleeper.sleeperId
      }
    }
  }

  String sid = getBedDeviceSide(device)
  debug('Getting sleep data for %s %s', device, ids[sid])
  // Interval can be W1 for a week, D1 for a day and M1 for a month.
  Map res = httpRequest('/rest/sleepData', this.&get, null, [
      interval: 'D1',
      sleeper: ids[sid],
      includeSlices: false,
      date: new Date().format("yyyy-MM-dd'T'HH:mm:ss")
  ])
  if (devdbg()) debug('Response data from SleepNumber: %s', res)
  return res
}

void loginAws() {
  debug 'Logging in (AWS)'
  if (state.session?.refreshToken) {
    state.session.accessToken = null
    try {
      JSONObject jsonBody = new JSONObject()
      jsonBody.put('RefreshToken', (String) state.session.refreshToken)
      jsonBody.put('ClientID', LOGIN_CLIENT_ID)
      Map params = [
              uri: LOGIN_URL + '/Prod/v1/token',
              requestContentType: 'application/json',
              contentType: 'application/json',
              headers: [
                      'Host': LOGIN_HOST,
                      'User-Agent': USER_AGENT,
              ],
              body: jsonBody.toString(),
              timeout: 20
      ]
      addHttpR('/Prod/v1/token')
      httpPut(params) { response ->
        if (response.success) {
          debug('refresh Success: (%s), %s', response.status, devdbg() ? response.data : 'redacted')
          state.session.accessToken = response.data.data.AccessToken
          // Refresh the access token 1 minute before it expires
          wrunIn(((Integer)response.data.data.ExpiresIn - 60).toLong(), 'login')
          state.status = 'Online'
        } else {
          // If there's a failure here then purge all session data to force clean slate
          state.session = null
          maybeLogError('login Failure refreshing Token: (%s) %s', response.status, response.data)
          state.status = sLOGINERR
        }
      }
    } catch (Exception e) {
      // If there's a failure here then purge all session data to force clean slate
      state.session = null
      maybeLogError(sLOGINERR, e)
      state.status = sLOGINERR
    }
  } else {
    state.session = null
    try {
      JSONObject jsonBody = new JSONObject()
      jsonBody.put('Email', getSettingStr('login'))
      jsonBody.put('Password', getSettingStr('password'))
      jsonBody.put('ClientID', LOGIN_CLIENT_ID)
      Map params = [
              uri: LOGIN_URL + '/Prod/v1/token',
              headers: [
                      'Host': LOGIN_HOST,
                      'User-Agent': USER_AGENT,
              ],
              body: jsonBody.toString(),
              timeout: 20
      ]
      addHttpR('/Prod/v1/token Json')
      httpPostJson(params) { response ->
        if (response.success) {
          debug('login Success: (%s), %s', response.status, devdbg() ? response.data : 'redacted')
          state.session = [:]
          state.session.accessToken = response.data.data.AccessToken
          state.session.refreshToken = response.data.data.RefreshToken
          // Refresh the access token 1 minute before it expires
          wrunIn(((Integer)response.data.data.ExpiresIn - 60).toLong(), 'login')
          // Get cookies since this is all new state
          loginCookie()
        } else {
          maybeLogError('login Failure getting Token: (%s) %s', response.status, response.data)
          state.status = sLOGINERR
        }
      }
    } catch (Exception e) {
      maybeLogError(sLOGINERR, e)
      state.status = sLOGINERR
    }
  }
}

void loginCookie() {
  state.session.cookies = null
  try {
    debug 'Getting cookie'
    Map params = [
            uri: API_URL + '/rest/account',
            headers: [
                    'Host': API_HOST,
                    'User-Agent': USER_AGENT,
                    'Authorization': state.session.accessToken,
            ],
            timeout: 20
    ]
    addHttpR('/rest/account')
    httpGet(params) { response ->
      if (response.success) {
        String expiration; expiration = sNL
        //Map sess=[:]
        //sess.key = response.data.key
        //sess.cookies = sBLK
        response.getHeaders('Set-Cookie').each {
          String[] cookieInfo = ((String) it.value).split(';')
          state.session.cookies = (String) state.session.cookies + cookieInfo[iZ] + ';'
          // find the expires value if it exists
          if (!expiration) {
            for (String cookie in cookieInfo) {
              if (cookie.contains('Expires=')) {
                expiration = cookie.split('=')[i1]
              }
            }
          }
        }
        Date refreshDate
        if (expiration == sNL) {
          maybeLogError('No expiration for any cookie found in response: %s', response.getHeaders('Set-Cookie'))
          refreshDate = new Date() + 1
        } else {
          refreshDate = new Date(ZonedDateTime.parse(expiration,
                  DateTimeFormatter.RFC_1123_DATE_TIME).minusDays(1L).toInstant().toEpochMilli())
        }
        runOnce(refreshDate, loginCookie)
        state.status = 'Online'
      } else {
        maybeLogError('login Failure getting Cookie: (%s), %s', response.status, response.data)
        state.status = sLOGINERR
      }
    }
  } catch (Exception e) {
    maybeLogError('loginCookie Error', e)
    state.status = sLOGINERR
  }
}

void loginOld() {
  debug 'Logging in (old)'
  state.session = null
  try {
    JSONObject jsonBody = new JSONObject()
    jsonBody.put('login', getSettingStr('login'))
    jsonBody.put('password', getSettingStr('password'))
    Map params = [
      uri: API_URL + '/rest/login',
      requestContentType: 'application/json',
      contentType: 'application/json',
      headers: [
        'Host': API_HOST,
        'User-Agent': USER_AGENT,
        'DNT': '1',
      ],
      body: jsonBody.toString(),
      timeout: i20
    ]
    addHttpR('/rest/login')
    httpPut(params) { response ->
      if (response.success) {
        debug('login Success: (%s), %s', response.status, devdbg() ? response.data : 'redacted')
        Map sess = [:]
        sess.key = response.data.key
        sess.cookies = sBLK
        response.getHeaders('Set-Cookie').each {
          sess.cookies = sess.cookies + ((String)it.value).split(';')[iZ] + ';'
        }
        state.session = sess
        state.status = 'Online'
      } else {
        maybeLogError('login Failure: (%s) %s', response.status, response.data)
        state.status = sLOGINERR
      }
    }
  } catch (Exception e) {
    maybeLogError(sLOGINERR, e)
    state.status = sLOGINERR
  }
}

@Field static final String sLOGINERR = 'Login Error'

void login() {
  if (getSettingB('useAwsOAuth')) {
    loginAws()
  } else {
    loginOld()
  }
  updateLabel()
}

/**
 * Adds a PUT HTTP request to the queue with the expectation that it will take approximately `duration`
 * time to run.  This means other enqueued requests may run after `duration`. 
 * Args may be:
 * body: Map
 * query: Map
 * path: String
 * runAfter: String (name of handler method to run after delay)
 */
@CompileStatic
void httpRequestQueue(Map args, Integer duration) {
  // Creating new classes appears to be forbidden so instead we just use a map to represent the
  // HTTP request data we want to persist in the queue.
  Map request = [
    duration: duration,
    path: args.path,
    body: args.body,
    query: args.query,
    runAfter: args.runAfter,
  ]
  requestQueue.add(request)
  handleRequestQueue()
}

// Only this method should be setting releaseLock to true.
@CompileStatic
void handleRequestQueue(Boolean releaseLock = false) {
  if (releaseLock) {
    mutex.release()
    debug 'released lock'
  }
  if (requestQueue.isEmpty()) return
  if (!lastLockTime) lastLockTime = now()
  // Get the oldest request in the queue to run.
  try {
    if (!mutex.tryAcquire()) {
      // If we can't obtain the lock it means one of two things:
      // 1. There's an existing operation and we should rightly skip.  In this case,
      //    the last thing the method does is re-run itself so this will clear itself up.
      // 2. There's an unintended failure which has lead to a failed lock release.  We detect
      //    this by checking the last time the lock was held and releasing the mutex if it's
      //    been too long.
      if ((now() - lastLockTime) > 120000L /* 2 minutes */) {
        // Due to potential race setting and reading the lock time,
        // wait 2s and check again before breaking it
        wpauseExecution(2000L)
        if ((now() - lastLockTime) > 120000L /* 2 minutes */) {
          lastLockTime = now()
          if (!mutex.tryAcquire()) {
            warn 'HTTP queue lock was held for more than 2 minutes, forcing release'
          }
          // In this case we should re-run.
          remTsVal(sLASTFAMILYDATA)
          handleRequestQueue(true)
        }
      }
      return
    }
    lastLockTime = now()
    debug 'got lock'
    Map request = (Map) requestQueue.poll()
    if (request) {
      ahttpRequest(request) // this can take a long time
    } else {
      mutex.release()
    }
  } catch (Exception e) {
    maybeLogError('Failed to run HTTP queue', e)
    mutex.release()
  }
}

@Field volatile static Map<String, Map> httpCntsMapFLD = [:]

private void addHttpR(String path) {
  String myId = gtAid()
  Map<String,Integer> cnts = httpCntsMapFLD[myId] ?: [:]
  cnts[path] = (cnts[path] ? cnts[path] : iZ) + i1
  httpCntsMapFLD[myId] = cnts
  //httpCntsMapFLD = httpCntsMapFLD
}

private Boolean isFuzion(String bedId) {
  return state.bedInfo[bedId].newApi
}

private Boolean fuzionHasFeature(String feature) {
  List<String> currentConfiguration = getState('systemConfiguration') as List<String>
  return currentConfiguration.contains(feature)
}

private Map makeBamKeyHttpRequest(String bedId, String key, List<String> bamKeyArgs = []) {
  return httpRequest(createBamKeyUrl(bedId, state.bedInfo[bedId].accountId as String),
      this.&put, createBamKeyArgs(key, bamKeyArgs))
}

/**
 * Creates a request for a Fuzion bed with the given key.
 */
private void addBamKeyRequestToQueue(String bedId, String key, List<String> bamKeyArgs = [],
                                     Integer duration = 0, String runAfter = null) {
  String accountId = state.bedInfo[bedId].accountId
  if (accountId == null) {
    error "No account id for bed ${bedId} available"
    return
  }
  httpRequestQueue(duration, path: createBamKeyUrl(bedId, accountId),
          body: createBamKeyArgs(key, bamKeyArgs), runAfter: runAfter)
}

@CompileStatic
private String createBamKeyUrl(String bedId, String accountId) {
  return "/rest/sn/v1/accounts/${accountId}/beds/${bedId}/bamkey"
}

@CompileStatic
private Map<String, String> createBamKeyArgs(String key, String arg) {
  return [
          key: BAM_KEY[key],
          args: arg,
          sourceApplication: APP_PREFIX
  ]
}

@CompileStatic
private Map<String, String> createBamKeyArgs(String key, List<String> args) {
  return createBamKeyArgs(key, args.join(" "))
}

@CompileStatic
Map fillParams(String path, Map body, Map query, Boolean useAwsO, Map sess, Boolean async) {
  String payload = body ? new JsonBuilder(body).toString() : sNL
  Map queryString; queryString = useAwsO ? new HashMap() : [_k: sess.key]
  if (query) {
    queryString = queryString + query
  }
  Map statusParams = [
          uri: API_URL,
          path: path,
          requestContentType: 'application/json',
          contentType: 'application/json',
          headers: [
                  'Host': API_HOST,
                  'User-Agent': USER_AGENT,
                  'Cookie': sess?.cookies,
                  'DNT': '1',
                  'Accept-Version': SN_APP_VERSION,
                  'X-App-Version': SN_APP_VERSION,
          ],
          query: queryString,
          body: payload,
          timeout: i20
  ]
  if (useAwsO) {
    statusParams.headers['Authorization'] = sess.accessToken
  }
  String s; s = "Sending request for ${path} with query ${queryString}"
  if (async) s+= ' : ASYNC'
  if (payload) s+= " : ${payload}"
  debug s
  return statusParams
}


void ahttpRequest(Map request) {
  httpRequest((String) request.path, this.&put, (Map) request.body, (Map) request.query, false, true, request)
}

Map httpRequest(String path, Closure method = this.&get, Map body = null, Map query = null,
                Boolean alreadyTriedRequest = false, Boolean async = false, Map qReq = null, Boolean wasAsync = false) {
  Map result; result = [:]
  Map sess = (Map) state.session
  Boolean useAwsO = getSettingB('useAwsOAuth')
  Boolean loginState = useAwsO ? !sess || !sess.accessToken : !sess || !sess.key
  if (loginState) {
    if (alreadyTriedRequest) {
      maybeLogError "httpRequest: Already attempted login but still no session key, giving up, path: $path"
      return result
    } else {
      login()
      result = httpRequest(path, method, body, query, true, false, qReq, async)
      if (wasAsync) {
        if (result) {
          finishAsyncReq(qReq, 200)
        } else {
          timeoutAreq(qReq)
        }
      }
      return result
    }
  }

  Map statusParams = fillParams(path, body, query, useAwsO, sess, async)
  String addstr
  addstr = query ? " ${query}" : sBLK
  addstr += async ? " ${async}" : sBLK
  addHttpR(path+addstr)
  try {
    if (async) {
      wrunInMillis(24000L, 'timeoutAreq', [data: qReq])
      asynchttpPut('ahttpRequestHandler', statusParams, [command: qReq])
      return [:]
    } else {
      method(statusParams) { response ->
        if (response.success) {
          result = response.data
        } else {
          maybeLogError('Failed request for %s %s with payload %s:(%s) %s',
                  path, statusParams.queryString, statusParams.body, response.status, response.data)
          state.status = sAPIERR
          updateLabel()
        }
      }
    }
    //if (result)debug "Response data from SleepNumber: ${result}"
    return result
  } catch (Exception e) {
    if (async) { unschedule('timeoutAreq') }
    if (e.toString().contains('Unauthorized') && !alreadyTriedRequest) {
      // The session is invalid so retry login before giving up.
      info 'Unauthorized, retrying login'
      login()
      result = httpRequest(path, method, body, query, true, false, qReq, async)
      if (async) { timeoutAreq(qReq) }
      return result
    } else {
      // There was some other error so retry if that hasn't already been done
      // otherwise give up.  Not Found errors won't improve with retry to don't
      // bother.
      if (!alreadyTriedRequest && !e.toString().contains('Not Found')) {
        maybeLogError('Retrying failed request %s\n%s', statusParams, e)
        result = httpRequest(path, method, body, query, true, false, qReq, async)
        if (async) { timeoutAreq() }
        return result
      } else {
        String err = 'Error making request %s\n%s'
        if (e.toString().contains('Not Found')) {
          // Don't bother polluting logs for Not Found errors as they are likely
          // either intentional (trying to figure out if outlet exists) or a code
          // bug.  In the latter case we still want diagnostic data so we use
          // debug logging.
          debug(err, statusParams, e)
          if (async) { timeoutAreq(qReq) }
          return result
        }
        maybeLogError(err, statusParams, e)
        state.status = sAPIERR
        updateLabel()
        if (async) { timeoutAreq(qReq) }
        return result
      }
    }
  }
}

@Field static final String sAPIERR = 'API Error'

void ahttpRequestHandler(resp, Map callbackData) {
  Map request = (Map) callbackData?.command
  unschedule('timeoutAreq')
  Integer rCode; rCode = (Integer) resp.status
  if (resp.hasError()) {
    debug "retrying async request as synchronous $rCode"
    httpRequest((String) request.path, (Closure) request.method, (Map) request.body,
            (Map) request.query, false, false, request)
  }
  finishAsyncReq(request, rCode)
}

void finishAsyncReq(Map request, Integer rCode) {
  Long rd; rd = ((Integer) request.duration).toLong()
  // Let this operation complete then process more requests and release the lock
  // throttle requests to 1 per second
  if (rd > 0L) wrunInMillis(Math.round(rd * 1000.0D), 'handleRequestQueue', [data: true])
  else wrunInMillis(Math.round(1000.0D), 'handleRequestQueue', [data: true])

  // If there was something to run after this then set that up as well.
  String ra = (String) request.runAfter
  if (ra) {
    remTsVal(sLASTFAMILYDATA)
    if (rd < 1L) rd = 4L
    wrunIn(rd, ra)// [overwrite:false])
  }
  debug "finishing async request $rCode; delay to next operation $rd seconds" + (ra ? ' with runafter' : sBLK)
}

void timeoutAreq(Map request = null) {
  warn "async request failure or timeout $request"
  handleRequestQueue(true)
  remTsVal(sLASTFAMILYDATA)
  wrunIn(10L, sREFRESHCHILDDEVICES)
}

/*------------------ Logging helpers ------------------*/
Boolean okToLogError() {
  String level = getSettingStr('logLevel')
  if (level != sNL && level.toInteger() == iZ) {
    return false
  }
  return true
}

/**
 * Only logs an error message if one wasn't logged within the last
 * N minutes where N is configurable.
*/
void maybeLogError(String msg, Object... args) {
  if (!okToLogError()) {
    return
  }
  Integer limit = getSettingI('limitErrorLogsMin')
  if (!limit /* off */
      || (now() - lastErrorLogTime) > (limit * 60 * 1000)) {
    error(msg, args)
    lastErrorLogTime = now()
  }
}

@CompileStatic
void error(String msg, Object... args) {
  if (!okToLogError()) {
    return
  }
  if (args.length == i1 && args[iZ] instanceof Exception) {
    logError(msg, (Exception) args[iZ])
  } else {
    logError(sprintf(msg, args))
  }
}

void debug(String msg, Object... args) {
  String level = getSettingStr('logLevel')
  if (getSettingB('enableDebugLogging') ||
          (level != sNL && level.toInteger() == i1)) {
    logDebug(sprintf(msg, args))
  }
}

void info(String msg, Object... args) {
  String level = getSettingStr('logLevel')
  if (getSettingB('enableDebugLogging') ||
          level == sNL ||
          (level.toInteger() >= i1 && level.toInteger() < i3)) {
    logInfo(sprintf(msg, args))
  }
}

void warn(String msg, Object... args) {
  String level = getSettingStr('logLevel')
  if (getSettingB('enableDebugLogging') ||
          level == sNL ||
          level.toInteger() > iZ) {
    logWarn(sprintf(msg, args))
  }
}

// Can't seem to use method reference to built-in so
// we create simple ones to pass around
def get(Map params, Closure closure) {
  httpGet(params, closure)
}

def put(Map params, Closure closure) {
  httpPut(params, closure)
}

@Field static final Pattern sBAM_PASS = Pattern.compile('PASS:')

@CompileStatic
List<String> processBamKeyResponse(Map response) {
  if (response.keySet().isEmpty() || response.containsKey('cdcResponse')) {
    // Bad response so return an empty list instead.
    warn("response from bam key request seems invalid: ${response}")
    return []
  } else {
    return ((String) response['cdcResponse']).replaceFirst(sBAM_PASS, '').tokenize()
  }
}

Long now() {
  return (Long) this.delegate.now()
}

/*------------------ Shared constants ------------------*/

@Field static final Boolean IS_BETA = true
@Field static final String appVersion = '3.3.0'  // public version
@Field static final String NAMESPACE = 'rvrolyk'
@Field static final String DRIVER_NAME = 'Sleep Number Bed'
@Field static final String APP_PREFIX = 'Sleep Number Controller'
@Field static final String BETA_SUFFIX = ' Beta'
static String getAPP_NAME() { APP_PREFIX + (IS_BETA ? BETA_SUFFIX : sBLK) }

/*------------------ Logging helpers ------------------*/

@Field static final String PURPLE = 'purple'
@Field static final String BLUE = '#0299b1'
@Field static final String GRAY = 'gray'
@Field static final String ORANGE = 'orange'
@Field static final String RED = 'red'

@Field static final String sLTH = '<'
@Field static final String sGTH = '>'

@CompileStatic
private static String logPrefix(String msg, String color = null) {
  String myMsg = msg.replaceAll(sLTH, '&lt;').replaceAll(sGTH, '&gt;')
  StringBuilder sb = new StringBuilder('<span ')
          .append("style='color:").append(GRAY).append(";'>")
          .append('[v').append(appVersion).append('] ')
          .append('</span>')
          .append("<span style='color:").append(color).append(";'>")
          .append(myMsg)
          .append('</span>')
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
  String a,b; a = sNL; b = sNL
  try {
    if (ex) {
      a = getExceptionMessageWithLine(ex)
      if (devdbg()) b = getStackTrace(ex)
    }
  } catch (ignored) {}
  if (a || b) {
    log.error logPrefix(a+' \n'+b, RED)
  }
}

@Field static final String sBLK = ''
@Field static final String sSPACE = ' '
@Field static final String sCLRORG = 'orange'
@Field static final String sLINEBR = '<br>'

@CompileStatic
static String span(String str, String clr = sNL, String sz = sNL, Boolean bld = false, Boolean br = false) {
  return str ? "<span ${(clr || sz || bld) ? "style='${clr ? "color: ${clr};" : sBLK}${sz ? "font-size: ${sz};" : sBLK}${bld ? "font-weight: bold;" : sBLK}'" : sBLK}>${str}</span>${br ? sLINEBR : sBLK}" : sBLK
}

/*------------------ Wrappers ------------------*/

Object wparseJson(String a) { return parseJson(a) }
Boolean wtimeOfDayIsBetween(Date s, Date st, Date v) { return (Boolean) timeOfDayIsBetween(s,st,v) }
Date wtoDateTime(String t) { return (Date) toDateTime(t) }
void wrunIn(Long t, String meth, Map options = null) { runIn(t,meth,options) }
private void wrunInMillis(Long t, String m, Map d) { runInMillis(t,m,d) }
private void wpauseExecution(Long t) { pauseExecution(t) }

private gtSetting(String nm) { return settings[nm] }

private String getSettingStr(String nm) { return (String) settings[nm] }
private Boolean getSettingB(String nm) { return (Boolean) settings[nm] }
private Integer getSettingI(String nm) { return (Integer) settings[nm] }

private getState(String nm) { return state.get(nm) }
private void setState(String nm, v) { state.put(nm, v) }

String gtAid() { return app.getId() }



/*------------------ In-memory timers ------------------*/

@Field volatile static Map<String, Map> tsDtMapFLD = [:]

@CompileStatic
private void updTsVal(String key, String dt = sNL) {
  String val = dt ?: getDtNow()
//  if (key in svdTSValsFLD) { updServerItem(key, val); return }

  String appId = gtAid()
  Map data = tsDtMapFLD[appId] ?: [:]
  if (key) data[key]=val
  tsDtMapFLD[appId] = data
}

@CompileStatic
private void remTsVal(key) {
  String appId = gtAid()
  Map data = tsDtMapFLD[appId] ?: [:]
  if (key) {
    if (key instanceof List) {
      List<String> aa = (List<String>) key
      for (String k in aa) {
        if (data.containsKey(k)) { data.remove(k) }
        //if (k in svdTSValsFLD) { remServerItem(k) }
      }
    } else {
      String sKey = (String) key
      if (data.containsKey(sKey)) { data.remove(sKey) }
      //if (sKey in svdTSValsFLD) { remServerItem(sKey) }
    }
    tsDtMapFLD[appId] = data
  }
}

//@Field static final List<String> svdTSValsFLD = ["lastCookieRrshDt", "lastServerWakeDt"]

@CompileStatic
private String getTsVal(String key) {
/*  if (key in svdTSValsFLD) {
    return (String)getServerItem(key)
  }*/
  String appId = gtAid()
  Map tsMap = tsDtMapFLD[appId]
  if (key && tsMap && tsMap[key]) { return (String) tsMap[key] }
  return sNL
}

@CompileStatic
Integer getLastTsValSecs(String val, Integer nullVal = 1000000) {
  String ts = val ? getTsVal(val) : sNL
  return ts ? GetTimeDiffSeconds(ts).toInteger() : nullVal
}

@CompileStatic
Long GetTimeDiffSeconds(String lastDate, String sender = sNL) {
  try {
    if (lastDate?.contains('dtNow')) { return 10000 }
    Date lastDt = Date.parse('E MMM dd HH:mm:ss z yyyy', lastDate)
    Long start = lastDt.getTime()
    Long stop = now()
    Long diff = (Long)((stop - start) / 1000L)
    return diff.abs()
  } catch (ex) {
    logError("GetTimeDiffSeconds Exception: (${sender ? "$sender | " : sBLK}lastDate: $lastDate): ${ex}", ex)
    return 10000L
  }
}

@CompileStatic
static String getDtNow() {
  Date now = new Date()
  return formatDt(now)
}

private static TimeZone mTZ() { return TimeZone.getDefault() } // (TimeZone)location.timeZone

@CompileStatic
static String formatDt(Date dt, Boolean tzChg = true) {
  SimpleDateFormat tf = new SimpleDateFormat('E MMM dd HH:mm:ss z yyyy')
  if (tzChg) { if (mTZ()) { tf.setTimeZone(mTZ()) } }
  return (String) tf.format(dt)
}

@Field static final String sSPCSB7 = '      │'
@Field static final String sSPCSB6 = '     │'
@Field static final String sSPCS6 = '      '
@Field static final String sSPCS5 = '     '
@Field static final String sSPCST = '┌─ '
@Field static final String sSPCSM = '├─ '
@Field static final String sSPCSE = '└─ '
@Field static final String sNWL = '\n'
@Field static final String sDBNL = '\n\n • '

@CompileStatic
static String spanStr(Boolean html, String s) { return html ? span(s) : s }

@CompileStatic
static String doLineStrt(Integer level, List<Boolean>newLevel) {
  String lineStrt; lineStrt = sNWL
  Boolean dB; dB = false
  Integer i
  for (i = iZ;  i < level; i++) {
    if (i + i1 < level) {
      if (!newLevel[i]) {
        if (!dB) { lineStrt+=sSPCSB7; dB = true }
        else lineStrt += sSPCSB6
      } else lineStrt += !dB ? sSPCS6 : sSPCS5
    } else lineStrt += !dB ? sSPCS6 : sSPCS5
  }
  return lineStrt
}

@CompileStatic
static String dumpListDesc(List data,Integer level, List<Boolean> lastLevel, String listLabel, Boolean html = false,
                           Boolean reorder = true) {
  String str; str = sBLK
  Integer cnt; cnt = i1
  List<Boolean> newLevel = lastLevel

  List list1 = data?.collect{it}
  Integer sz = list1.size()
  for (Object par in list1) {
    String lbl = listLabel + "[${cnt-i1}]".toString()
    if (par instanceof Map) {
      Map newmap = [:]
      newmap[lbl] = (Map) par
      Boolean t1 = cnt == sz
      newLevel[level] = t1
      str += dumpMapDesc(newmap, level, newLevel, cnt, sz, !t1, html, reorder)
    } else if (par instanceof List || par instanceof ArrayList) {
      Map newmap = [:]
      newmap[lbl] = par
      Boolean t1 = cnt == sz
      newLevel[level] = t1
      str += dumpMapDesc(newmap, level, newLevel, cnt, sz, !t1, html, reorder)
    } else {
      String lineStrt
      lineStrt = doLineStrt(level,lastLevel)
      lineStrt += cnt == i1 && sz > i1 ? sSPCST : (cnt < sz ? sSPCSM:sSPCSE)
      str+=spanStr(html, lineStrt + lbl + ": ${par} (${objType(par)})".toString() )
    }
    cnt += i1
  }
  return str
}

@CompileStatic
static String dumpMapDesc(Map data, Integer level, List<Boolean> lastLevel, Integer listCnt = null,
                          Integer listSz = null, Boolean listCall = false, Boolean html = false,
                          Boolean reorder = true) {
  String str; str = sBLK
  Integer cnt; cnt = i1
  Integer sz = data?.size()
  Map svMap, svLMap, newMap; svMap = [:]; svLMap = [:]; newMap = [:]
  for (par in data) {
    String k = (String) par.key
    def v = par.value
    if (reorder && v instanceof Map) {
      svMap += [(k): v]
    } else if (reorder && (v instanceof List || v instanceof ArrayList)) {
      svLMap += [(k): v]
    } else newMap += [(k):v]
  }
  newMap += svMap + svLMap
  Integer lvlpls = level + i1
  for (par in newMap) {
    String lineStrt
    List<Boolean> newLevel = lastLevel
    Boolean thisIsLast = cnt == sz && !listCall
    if (level>iZ) newLevel[(level-i1)] = thisIsLast
    Boolean theLast
    theLast = thisIsLast
    if (level == iZ) lineStrt = sDBNL
    else {
      theLast = theLast && thisIsLast
      lineStrt = doLineStrt(level, newLevel)
      if (listSz && listCnt && listCall) lineStrt += listCnt == i1 && listSz > i1 ? sSPCST : (listCnt<listSz ? sSPCSM : sSPCSE)
      else lineStrt += ((cnt<sz || listCall) && !thisIsLast) ? sSPCSM : sSPCSE
    }
    String k = (String) par.key
    def v = par.value
    String objType = objType(v)
    if (v instanceof Map) {
      str += spanStr(html, lineStrt + "${k}: (${objType})".toString() )
      newLevel[lvlpls] = theLast
      str += dumpMapDesc((Map) v, lvlpls, newLevel, null, null, false, html, reorder)
    }
    else if (v instanceof List || v instanceof ArrayList) {
      str += spanStr(html, lineStrt + "${k}: [${objType}]".toString() )
      newLevel[lvlpls] = theLast
      str += dumpListDesc((List) v, lvlpls, newLevel, sBLK, html, reorder)
    }
    else{
      str += spanStr(html, lineStrt + "${k}: (${v}) (${objType})".toString() )
    }
    cnt += i1
  }
  return str
}

@CompileStatic
static String objType(obj) { return span(myObj(obj), sCLRORG) }

@CompileStatic
static String getMapDescStr(Map data, Boolean reorder = true) {
  List<Boolean> lastLevel = [true]
  String str = dumpMapDesc(data, iZ, lastLevel, null, null, false, true, reorder)
  return str != sBLK ? str : 'No Data was returned'
}

static String myObj(obj) {
  if (obj instanceof String) return 'String'
  else if (obj instanceof Map) return 'Map'
  else if (obj instanceof List) return 'List'
  else if (obj instanceof ArrayList) return 'ArrayList'
  else if (obj instanceof BigInteger) return 'BigInt'
  else if (obj instanceof Long) return 'Long'
  else if (obj instanceof Integer) return 'Int'
  else if (obj instanceof Boolean) return 'Bool'
  else if (obj instanceof BigDecimal) return 'BigDec'
  else if (obj instanceof Double) return 'Double'
  else if (obj instanceof Float) return 'Float'
  else if (obj instanceof Byte) return 'Byte'
  else if (obj instanceof com.hubitat.app.DeviceWrapper) return 'Device'
  else return 'unknown'
}

// vim: tabstop=2 shiftwidth=2 expandtab

