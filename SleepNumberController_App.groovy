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

import groovy.json.*
import groovy.transform.Field

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import groovy.transform.CompileStatic
import java.util.concurrent.Semaphore
import org.json.JSONObject
import java.text.SimpleDateFormat

@Field static final String appVersionFLD  = '3.1.9'

@Field static ConcurrentLinkedQueue requestQueue = new ConcurrentLinkedQueue()
@Field static Semaphore mutex = new Semaphore(1)
@Field volatile static Long lastLockTime = 0L
@Field static Long lastErrorLogTime = 0L

@Field static final String sNL=(String)null
@Field static final String sSTON='On'
@Field static final String sSTOFF='Off'
@Field static final String sRIGHT='Right'
@Field static final String sLEFT='Left'

@Field static final String sNUM='number'
@Field static final String sTXT='text'
@Field static final String sENUM='enum'
@Field static final String sBOOL='bool'
@Field static final String sON='on'
@Field static final String sOFF='off'
@Field static final String sSWITCH='switch'
@Field static final String sPRESENCE='presence'
@Field static final String sNM='name'
@Field static final String sVL='value'
@Field static final String sTYP='type'
@Field static final String sTIT='title'
@Field static final String sDESC='description'

@Field static final String sHEAD='head'
@Field static final String sFOOT='foot'
@Field static final String sFOOTWMR='foot warmer'
@Field static final String sOUTLET='outlet'
@Field static final String sUNDERBEDLIGHT='underbedlight'

@Field static final Integer iZ=0
@Field static final Integer i1=1
@Field static final Integer i2=2
@Field static final Integer i3=3
@Field static final Integer i4=4
@Field static final Integer i20=20

@Field static final String DRIVER_NAME = "Sleep Number Bed"
@Field static final String NAMESPACE = "rvrolyk"
@Field static final String API_HOST = "prod-api.sleepiq.sleepnumber.com"
@Field final String API_URL = "https://" + API_HOST
@Field final String LOGIN_HOST = "l06it26kuh.execute-api.us-east-1.amazonaws.com"
@Field final String LOGIN_URL = "https://" + LOGIN_HOST
@Field final String LOGIN_CLIENT_ID = "jpapgmsdvsh9rikn4ujkodala"
@Field final String USER_AGENT = "SleepIQ/1669639706 CFNetwork/1399 Darwin/22.1.0"
//'''\
//Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36'''

@Field static final ArrayList<String> VALID_ACTUATORS = ["H", "F"]
@Field static final ArrayList<Integer> VALID_SPEEDS = [0, 1, 2, 3]
@Field static final ArrayList<Integer> VALID_WARMING_TIMES = [30, 60, 120, 180, 240, 300, 360]
@Field static final ArrayList<Integer> VALID_WARMING_TEMPS = [0, 31, 57, 72]
@Field static final ArrayList<Integer> VALID_PRESET_TIMES = [0, 15, 30, 45, 60, 120, 180]
@Field static final ArrayList<Integer> VALID_PRESETS = [1, 2, 3, 4, 5, 6]
@Field static final ArrayList<Integer> VALID_LIGHT_TIMES = [15, 30, 45, 60, 120, 180]
@Field static final ArrayList<Integer> VALID_LIGHT_BRIGHTNESS = [1, 30, 100]
@Field static final Map<String, String> LOG_LEVELS = ["0": "Off", "1": "Debug", "2": "Info", "3": "Warn"]

definition(
  (sNM): "Sleep Number Controller",
  namespace: NAMESPACE,
  author: "Russ Vrolyk",
  (sDESC): "Control your Sleep Number Flexfit bed.",
  category: "Integrations",
  iconUrl: sBLK,
  iconX2Url: sBLK,
  importUrl: "https://github.com/rvrolyk/SleepNumberController/blob/master/SleepNumberController_App.groovy"
)

preferences {
  page ((sNM): "homePage", install: true, uninstall: true)
  page ((sNM): "findBedPage")
  page ((sNM): "selectBedPage")
  page ((sNM): "createBedPage")
  page ((sNM): "diagnosticsPage")
}

/**
 * Required handler for pause button.
 */
def appButtonHandler(btn) {
  if (btn == "pause") {
    state.paused = !(Boolean)state.paused
    if ((Boolean)state.paused) {
      debug "Paused, unscheduling..."
      unschedule()
      unsubscribe()
      updateLabel()
    } else {
      login()
      initialize()
    }
  }
}

def homePage() {
  List currentDevices = getBedDeviceData()

  dynamicPage((sNM): "homePage") {
    if ((Boolean)state.paused) {
      state.pauseButtonName = "Resume"
    } else {
      state.pauseButtonName = "Pause"
    }
    section(sBLK) {
      input ((sNM): "pause", (sTYP): "button", (sTIT): (String)state.pauseButtonName)
    }
    section("<b>Settings</b>") {
      input ((sNM): "login", (sTYP): sTXT, (sTIT): "sleepnumber.com email",
          (sDESC): "Email address you use with Sleep Number", submitOnChange: true)
      input ((sNM): "password", (sTYP): "password", (sTIT): "sleepnumber.com password",
          (sDESC): "Password you use with Sleep Number", submitOnChange: true)
      // User may opt for constant refresh or a variable one.
      Boolean defaultVariableRefresh = settings.variableRefresh != null && !(Boolean)settings.variableRefresh ? false : (Integer)settings.refreshInterval == null
      input ("variableRefresh", sBOOL, (sTIT): "Use variable refresh interval? (recommended)", defaultValue: defaultVariableRefresh,
         submitOnChange: true)
      if (defaultVariableRefresh || (Boolean)settings.variableRefresh) {
        input ((sNM): "dayInterval", (sTYP): sNUM, (sTIT): "Daytime Refresh Interval (minutes; 0-59)",
            (sDESC): "How often to refresh bed state during the day", defaultValue: 30)
        input ((sNM): "nightInterval", (sTYP): sNUM, (sTIT): "Nighttime Refresh Interval (minutes; 0-59)",
              (sDESC): "How often to refresh bed state during the night", defaultValue: i1)
        input "variableRefreshModes", sBOOL, (sTIT): "Use modes to control variable refresh interval", defaultValue: false, submitOnChange: true
        if ((Boolean)settings.variableRefreshModes) {
          input ((sNM): "nightMode", (sTYP): "mode", (sTIT): "Modes for night (anything else will be day)", multiple: true, submitOnChange: true)
          app.removeSetting('dayStart')
          app.removeSetting('nightStart')
        } else {
          input ((sNM): "dayStart", (sTYP): "time", (sTIT): "Day start time",
              (sDESC): "Time when day will start if both sides are out of bed for more than 5 minutes", submitOnChange: true)
          input ((sNM): "nightStart", (sTYP): "time", (sTIT): "Night start time", (sDESC): "Time when night will start", submitOnChange: true)
          app.removeSetting('nightMode')
        }
        app.removeSetting('refreshInterval')
      } else {
        input ((sNM): "refreshInterval", (sTYP): sNUM, (sTIT): "Refresh Interval (minutes; 0-59)",
            (sDESC): "How often to refresh bed state", defaultValue: i1)
        app.removeSetting('dayInterval')
        app.removeSetting('nightInterval')
        app.removeSetting('dayStart')
        app.removeSetting('nightStart')
        app.removeSetting('nightMode')
      }
    }

    section("<b>Bed Management</b>") {
      if (!(String)settings.login || !(String)settings.password) {
        paragraph "Add login and password to find beds"
      } else {
        if (currentDevices.size() > iZ) {
          paragraph "Current beds"
          currentDevices.each { Map device ->
            String output
            output = sBLK
            if (device.isChild) {
              output += "            "
            } else {
              output += device.bedId
            }
            output += " (<a href=\"/device/edit/${device.deviceId}\">dev:${device.deviceId}</a>) / ${device.name} / ${device.side} / ${device.type}"
            paragraph output
          }
          paragraph "<br>Note: <i>To remove a device remove it from the Devices list</i>"
        }
        // Only show bed search if user entered creds
        if ((String)settings.login && (String)settings.password) {
          href "findBedPage", (sTIT): "Create or Modify Bed", (sDESC): "Search for beds"
        }
      }
    }

    section((sTIT): sBLK) {
      href url: "https://github.com/rvrolyk/SleepNumberController", style: "external", required: false, (sTIT): "Documentation", (sDESC): "Tap to open browser"
    }
 
    section((sTIT): sBLK) {
      href url: "https://www.paypal.me/rvrolyk", style: "external", required: false, (sTIT): "Donations", (sDESC): "Tap to open browser for PayPal"
    }
       
    section((sTIT): "<b>Advanced Settings</b>") {
      String defaultName
      defaultName = "Sleep Number Controller"
      if (state.displayName) {
        defaultName = state.displayName
        app.updateLabel(defaultName)
      }
      label ((sTIT): "Assign an app name", required: false, defaultValue: defaultName)
      input ((sNM): "modes", (sTYP): "mode", (sTIT): "Set for specific mode(s)", required: false, multiple: true, submitOnChange: true)
      input ((sNM): "switchToDisable", (sTYP): "capability.switch", (sTIT): "Switch to disable refreshes", required: false, submitOnChange: true)
      input "enableDebugLogging", sBOOL, (sTIT): "Enable debug logging for 30m?", defaultValue: false, required: true, submitOnChange: true
      input "logLevel", sENUM, (sTIT): "Choose the logging level", defaultValue: "2", submitOnChange: true, options: LOG_LEVELS
      input "limitErrorLogsMin", sNUM, (sTIT): "How often to allow error logs (minutes), 0 for all the time. <br><font size=-1>(Only applies when log level is not off)</font> ", defaultValue: 0, submitOnChange: true
      if ((String)settings.login && (String)settings.password) {
        href "diagnosticsPage", (sTIT): "Diagnostics", (sDESC): "Show diagnostic info"
      }
      input "useAwsOAuth", sBOOL, (sTIT): "(Beta) Use AWS OAuth", required: false, submitOnChange: true, defaultValue: false
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
  app.removeSetting('hubitatQueryString')
  app.removeSetting('requestType')
  app.removeSetting('requestPath')
  app.removeSetting('requestBody')
  app.removeSetting('requestQuery')
  state.remove('variableRefresh')
  state.session = null // next run will refresh all tokens/cookies
  initialize()
  if ((Boolean)settings.enableDebugLogging) {
    runIn(7200L, logsOff)
  }
}

void logsOff() {
  if ((Boolean)settings.enableDebugLogging) {
    // Log this information regardless of user setting.
    logInfo "debug logging disabled..."
    app.updateSetting "enableDebugLogging", [(sVL): "false", (sTYP): sBOOL]
  }
}

def initialize() {
  Integer ri= (Integer)settings.refreshInterval
  if (!(Boolean)settings.variableRefresh && ri <= iZ) {
    logError "Invalid refresh interval ${ri}"
  }
  Integer di= (Integer)settings.dayInterval
  Integer ni= (Integer)settings.nightInterval
  if ((Boolean)settings.variableRefresh && (di <= iZ || ni <= iZ)) {
    logError "Invalid refresh intervals ${di} or ${ni}"
  }
  if ((Boolean)settings.variableRefreshModes) {
    subscribe(location, "mode", configureVariableRefreshInterval)
  }
  remTsVal("lastBedInfoUpdDt")
  remTsVal("lastBedDataUpdDt")
  remTsVal("lastFamilyDataUpdDt")
  remTsVal("lastPrivacyDataUpdDt")
  remTsVal("lastSleepNumDataUpdDt")
  remTsVal("lastSleeperDataUpdDt")
  setRefreshInterval(0.0 /* force picking from settings */, "" /* ignored */)
  initializeBedInfo()
  refreshChildDevices()
  updateLabel()
}

void updateLabel() {
  // Store the user's original label in state.displayName
  String al= (String)app.label
  if (!al.contains("<span") && (String)state.displayName != al) {
    state.displayName = al
  }
  if ((String)state.status || (Boolean)state.paused) {
    String status, nstatus
    status = (String)state.status
    nstatus=status
    String label
    label = "${state.displayName} <span style=color:"
    if ((Boolean)state.paused) {
      nstatus = "(Paused)"
      label += "red"
    } else if (status == "Online") {
      label += "green"
    } else if (status.contains("Login")) {
      label += "red"
    } else {
      label += "orange"
    }
    label += ">${nstatus}</span>"
    app.updateLabel(label)
  }
}

void initializeBedInfo() {
  if(debugOk()) logDebug "Initializing bed info"
  Map bedInfo = getBeds()
  if(bedInfo){
    updTsVal("lastBedInfoUpdDt")
    Map<String,Map> sbedInfo = [:]
    state.bedInfo = sbedInfo
    ((List<Map>)bedInfo.beds).each() { Map bed ->
      String bdId=bed.bedId.toString()
      if(debugOk()) logDebug "Bed id ${bdId}"
      if (!sbedInfo.containsKey(bdId)) {
        sbedInfo[bdId] = [:]
      }
      List<String> components = []
      for (Map component in (List<Map>)bed.components) {
        if ((String)component.type == "Base"
                && ((String)component.model).toLowerCase().contains("integrated")) {
          // Integrated bases need to be treated separately as they don't appear to have
          // foundation status endpoints so don't lump this with a base type directly.
          components << "Integrated Base"
        } else {
          components << (String)component.type
        }
      }
      sbedInfo[bdId].components = components
      if (((List<String>)((Map)sbedInfo[bdId]).components).contains("Base"))
        Map foundationInfo = getFoundationSystem(bdId)
      // has fsBoardFeatures
      //    def __feature_check(self, value, digit):
      //        return ((1 << digit) & value) > 0
      //        feature['boardIsASingle'] = self.__feature_check(fs_board_features, 0)
      //        feature['hasMassageAndLight'] = self.__feature_check(fs_board_features, 1)
      //        feature['hasFootControl'] = self.__feature_check(fs_board_features, 2)
      //        feature['hasFootWarming'] = self.__feature_check(fs_board_features, 3)
      //        feature['hasUnderbedLight'] = self.__feature_check(fs_board_features, 4)
      //        feature['leftUnderbedLightPMW'] = getattr(fs, 'fsLeftUnderbedLightPWM')
      //        feature['rightUnderbedLightPMW'] = getattr(fs, 'fsRightUnderbedLightPWM')
      //
      //        if feature['hasMassageAndLight']:
      //            feature['hasUnderbedLight'] = True
      //        if feature['splitKing'] or feature['splitHead']:
      //            feature['boardIsASingle'] = False
      // fsBedType
      // if fs_bed_type == 0:
      //            feature['single'] = True
      //        elif fs_bed_type == 1:
      //            feature['splitHead'] = True
      //        elif fs_bed_type == 2:
      //            feature['splitKing'] = True
      //        elif fs_bed_type == 3:
      //            feature['easternKing'] = True
      state.bedInfo = sbedInfo
    }
  }
  if (!state.bedInfo) {
    warn "No bed state set up"
  }
}

/**
 * Gets all bed child devices even if they're in a virtual container.
 * Will not return the virtual container(s) or children of a parent
 * device.
 */
List getBedDevices() {
  List children = []
  // If any child is a virtual container, iterate that too
  getChildDevices().each { child ->
    if (child.hasAttribute("containerSize")) {
      children.addAll(child.childList())
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
  List devices = getBedDevices()
  List<Map> output = []
  devices.each { device ->
    String side = gtSide(device)
    String bedId = gtId(device)
    String type = gtType(device) ?: "Parent"

    output << [
      (sNM): (String)device.label,
      (sTYP): type,
      side: side,
      deviceId: device.id,
      bedId: bedId,
      isChild: false,
    ]
    device.getChildDevices().each { child ->
      output << [
        (sNM): child.label,
        (sTYP): device.getChildType(child.deviceNetworkId),
        side: side,
        deviceId: child.id,
        bedId: bedId,
        isChild: true,
      ]
    }
  }
  return output
}

List<String> getBedDeviceTypes() {
  List<Map> data = getBedDeviceData()
  // TODO: Consider splitting this by side or even by bed.
  // SKipping for now as most are probably using the same device types
  // per side and probably only have one bed.
  return data.collect { (String)it.type }
}

// Use with #schedule as apparently it's not good to mix #runIn method call
// and #schedule method call.
void scheduledRefreshChildDevices() {
  refreshChildDevices()
  if ((Boolean)settings.variableRefresh) {
    // If we're using variable refresh then try to reconfigure it since bed states
    // have been updated and we may be in daytime.
    configureVariableRefreshInterval()
  }
}

void refreshChildDevices() {
  // Only refresh if mode is a selected one
  List setm=(List)settings.modes
  if (setm && !setm.contains(location.mode)) {
    if(debugOk()) logDebug "Skipping refresh, not the right mode"
    return
  }
  // If there's a switch defined and it's on, don't bother refreshing at all
  def dev= settings.switchToDisable
  if (dev && dev.currentValue(sSWITCH) == sON) {
    if(debugOk()) logDebug "Skipping refresh, switch to disable is on"
    return
  }

  wrunIn(4L, "doRefresh")
}

void doRefresh(){
  if(debugOk()) logDebug "Refresh child devices"
  getBedData()
  updateLabel()
}

/**
 * Called by driver when user triggers poll.
 */
void refreshChildDevices(Map ignored, String ignoredDevId) {
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
  debug "setRefreshInterval(${val})"
  def random = new Random()
  Integer randomInt = random.nextInt(40) + i4
  if (val && val > iZ) {
    schedule("${randomInt} /${val} * * * ?", "scheduledRefreshChildDevices")
  } else {
    if (!(Boolean)settings.variableRefresh) {
      Integer ival=(Integer)settings.refreshInterval
      debug "Resetting interval to ${ival}"
      schedule("${randomInt} /${ival} * * * ?", "scheduledRefreshChildDevices")
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

  if ((Boolean)settings.variableRefreshModes) {
    night = ((List)settings.nightMode).contains(location.mode)
  } else {
    // Gather presence state of all child devices
    List presentChildren = getBedDevices().findAll {
      String t= gtType(it)
      (!t || t == sPRESENCE) && (Boolean)it.isPresent()
    }
    Date now = new Date()
    if (wtimeOfDayIsBetween(wtoDateTime((String)settings.dayStart), wtoDateTime((String)settings.nightStart), now)) {
      if (presentChildren.size() > iZ) return // if someone is still in bed, don't change anything
      night = false
    } else {
      night = true
    }
  }

  String s; s=sNL
  Integer ival; ival=null
  if (night) {
    // Don't bother setting the schedule if we are already set to night.
    if ((String)state.variableRefresh != "night") {
      ival=(Integer)settings.nightInterval
      s="night"
    }
  } else if ((String)state.variableRefresh != "day") {
    ival=(Integer)settings.dayInterval
    s="day"
  }
  if(s){
    Random random = new Random()
    Integer randomInt = random.nextInt(40) + i4
    info "Setting interval to ${s}. Refreshing every ${ival} minutes."
    schedule("${randomInt} /${ival} * * * ?", "scheduledRefreshChildDevices")
    state.variableRefresh = s
  }
}

def findBedPage() {
  Map responseData = getBedData()
  List devices = getBedDevices()
  List<String> sidesSeen = []
  List<String> childDevices = []
  List<Map> beds= (List<Map>)responseData.beds
  dynamicPage((sNM): "findBedPage") {
    if (beds.size() > iZ) {
      String l=sLEFT
      String ls=l+' side'
      String r=sRIGHT
      String rs=r+' side'
      beds.each { Map bed ->
        String bdId=bed.bedId.toString()
        section("Bed: ${bdId}") {
          if (devices.size() > iZ) {
            for (dev in devices) {
              String t= gtType(dev)
              if (!t || t == sPRESENCE) {
                String s= gtSide(dev)
                if (!t) { childDevices << s }
                sidesSeen << s
                app.updateSetting("newDeviceName", [(sVL): sBLK, (sTYP): sTXT])
                href "selectBedPage", (sTIT): dev.label, (sDESC): "Click to modify",
                    params: [bedId: bdId, side: s, label: dev.label]
              }
            }
            if (childDevices.size() < i2) {
              input "createNewChildDevices", sBOOL, (sTIT): "Create new child device types", defaultValue: false, submitOnChange: true
              if ((Boolean)settings.createNewChildDevices) {
                if (!childDevices.contains(l)) { addSelect(ls, l, bdId) }
                if (!childDevices.contains(r)) { addSelect(rs, r, bdId) }
              }
            }
          }
          if (!sidesSeen.contains(l)) { addSelect(ls, l, bdId) }
          if (!sidesSeen.contains(r)) { addSelect(rs, r, bdId) }
        }
      }
    } else {
      section {
        paragraph "No Beds Found"
      }
    }
  }
}

private addSelect(String title, String side, String bdId){
  app.updateSetting("newDeviceName", [(sVL): sBLK, (sTYP): sTXT])
  href "selectBedPage", (sTIT): title, (sDESC): "Click to create",
          params: [bedId: bdId, side: side, label: sBLK]
}

static String presenceText(presence) {
  return presence ? "Present" : "Not Present"
}

def selectBedPage(Map params) {
  Integer lastUpd = getLastTsValSecs("lastBedInfoUpdDt")
  if(lastUpd > 300){
    initializeBedInfo()
  }
  //app.updateSetting("newDeviceName", [(sVL): sBLK, (sTYP): sTXT])
  dynamicPage((sNM): "selectBedPage") {
    String bdId=params?.bedId
    if (!bdId) {
      section {
        href "homePage", (sTIT): "Home", (sDESC): sNL
      }
      return
    }
    section {
      paragraph """<b>Instructions</b>
Enter a name, then choose whether or not to use child devices or a virtual container for the devices and then choose the types of devices to create.
Note that if using child devices, the parent device will contain all the special commands along with bed specific status while the children are simple
switches or dimmers.  Otherwise, all devices are the same on Hubitat, the only difference is how they behave to dim and on/off commands.  This is so
that they may be used with external assistants such as Google Assistant or Amazon Alexa.  If you don't care about such use cases (and only want
RM control or just presence), you can just use the presence type.
<br>
See <a href="https://community.hubitat.com/t/release-virtual-container-driver/4440" target=_blank>this post</a> for virtual container.
"""
        paragraph """<b>Device information</b>
Bed ID: ${bdId}
Side: ${params.side}
""" 
    }
    section {
      String lbl= (String)params.label
      String nn= (String)settings.newDeviceName
      String name = nn?.trim() ? nn : lbl?.trim() ? lbl : nn
      if(!settings.newDeviceName)app.updateSetting("newDeviceName", [(sVL): name, (sTYP): sTXT])
      input "newDeviceName", sTXT, (sTIT): "Device Name", defaultValue: name,
          (sDESC): "What prefix do you want for the devices?", submitOnChange: true,
          required: true
      input "useChildDevices", sBOOL, (sTIT): "Use child devices? (recommended)", defaultValue: true,
         submitOnChange: true
      if (!(Boolean)settings.useChildDevices) {
        input "useContainer", sBOOL, (sTIT): "Use virtual container?", defaultValue: false,
           submitOnChange: true
      }
      String pside= ((String)params.side).toLowerCase()
      paragraph "A presence type device exposes on/off as switching to a preset level (on) and flat (off).  Dimming will change the Sleep Number."
      if ((Boolean)settings.useChildDevices) {
        paragraph "This is the parent device when child devices are used"
        app.updateSetting "createPresence", [(sVL): "true", (sTYP): sBOOL]
        settings.createPresence = true
      } else {
        input "createPresence", sBOOL,
            (sTIT): "Create presence device for ${pside} side?",
            defaultValue: true, submitOnChange: true
      }
      paragraph "A head type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the head position (0 is flat, 100 is fully raised)."
      input "createHeadControl", sBOOL,
         (sTIT): "Create device to control the head of the ${pside} side?",
         defaultValue: true, submitOnChange: true
      paragraph "A foot type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the foot position (0 is flat, 100 is fully raised)."
      input "createFootControl", sBOOL,
         (sTIT): "Create device to control the foot of the ${pside} side?",
         defaultValue: true, submitOnChange: true
      if (((List<String>)((Map)state.bedInfo[bdId]).components).contains("Warming")) {
        paragraph "A foot type device exposes on/off as switching the foot warming on or off.  Dimming will change the heat levels (1: low, 2: medium, 3: high)."
        input "createFootWarmer", sBOOL,
           (sTIT): "Create device to control the foot warmer of the ${pside} side?",
           defaultValue: true, submitOnChange: true
      }
      if ((Boolean)settings.useChildDevices) {
        determineUnderbedLightSetup(bdId)
        paragraph "Underbed lighting creates a dimmer allowing the light to be turned on or off at different levels with timer based on parent device preference."
        input "createUnderbedLighting", sBOOL,
         (sTIT): "Create device to control the underbed lighting of the ${pside} side?",
           defaultValue: false, submitOnChange: true
        if (((List)((Map)state.bedInfo[bdId]).outlets).size() > i1) {
          paragraph "Outlet creates a switch allowing foundation outlet for this side to be turned on or off."
          input "createOutlet", sBOOL,
           (sTIT): "Create device to control the outlet of the ${pside} side?",
             defaultValue: false, submitOnChange: true
        }
      }
    }
    section {
      String msg
      msg = "Will create the following devices"
      String containerName
      containerName = sBLK
      List<String> types = []
      if ((Boolean)settings.useChildDevices) {
        app.updateSetting "useContainer", [(sVL): "false", (sTYP): sBOOL]
        settings.useContainer = false
        msg += " with each side as a primary device and each type as a child device of the side"
      } else if ((Boolean)settings.useContainer) {
        containerName = "${(String)settings.newDeviceName} Container"
        msg += " in virtual container '${containerName}'"
      }
      msg += ":<ol>"
      if ((Boolean)settings.createPresence) {
        msg += "<li>${createDeviceLabel((String)settings.newDeviceName, sPRESENCE)}</li>"
        types.add(sPRESENCE)
      }
      if ((Boolean)settings.createHeadControl) {
        msg += "<li>${createDeviceLabel((String)settings.newDeviceName, sHEAD)}</li>"
        types.add(sHEAD)
      }
      if ((Boolean)settings.createFootControl) {
        msg += "<li>${createDeviceLabel((String)settings.newDeviceName, sFOOT)}</li>"
        types.add(sFOOT)
      }
      if ((Boolean)settings.createFootWarmer) {
        msg += "<li>${createDeviceLabel((String)settings.newDeviceName, sFOOTWMR)}</li>"
        types.add(sFOOTWMR)
      }
      if ((Boolean)settings.createUnderbedLighting && (Boolean)settings.useChildDevices) {
        msg += "<li>${createDeviceLabel((String)settings.newDeviceName, sUNDERBEDLIGHT)}</li>"
        types.add(sUNDERBEDLIGHT)
      }
      if ((Boolean)settings.createOutlet && (Boolean)settings.useChildDevices) {
        msg += "<li>${createDeviceLabel((String)settings.newDeviceName, sOUTLET)}</li>"
        types.add(sOUTLET)
      }
      msg += "</ol>"
      paragraph msg
      href "createBedPage", (sTIT): "Create Devices", (sDESC): sNL,
      params: [
        presence: params.present,
        bedId: bdId,
        side: params.side,
        useChildDevices: (Boolean)settings.useChildDevices,
        useContainer: (Boolean)settings.useContainer,
        containerName: containerName,
        types: types
      ]
    }
  }
}

static String createDeviceLabel(String name, String type) {
  switch (type) {
    case sPRESENCE:
      return "${name}"
    case sHEAD:
      return "${name} Head"
    case sFOOT:
      return "${name} Foot"
    case sFOOTWMR:
      return "${name} Foot Warmer"
    case sUNDERBEDLIGHT:
      return "${name} Underbed Light"
    case sOUTLET:
      return "${name} Outlet"
    default:
      return "${name} Unknown"
  }
}

def createBedPage(Map params) {
  def container; container = null
  if ((Boolean)params.useContainer) {
    container = createContainer((String)params.bedId, (String)params.containerName, (String)params.side)
  }
  List existingDevices = getBedDevices()
  List devices = []
  // TODO: Consider allowing more than one identical device for debug purposes.
  if ((Boolean)params.useChildDevices) {
    // Bed Ids seem to always be negative so convert to positive for the device
    // id for better formatting.
    Long bedId = Math.round(Math.abs(((String)params.bedId).toDouble()))
    String deviceId = "sleepnumber.${bedId}.${params.side}".toString()
    String label = createDeviceLabel((String)settings.newDeviceName, sPRESENCE)
    def parent
    parent = existingDevices.find{ (String)it.deviceNetworkId == deviceId }
    if (parent) {
      info "Parent device ${deviceId} already exists"
    } else {
      debug "Creating parent device ${deviceId}"
      parent = addChildDevice(NAMESPACE, DRIVER_NAME, deviceId, null, [label: label])
      parent.setStatus(params.presence)
      parent.setBedId(params.bedId)
      parent.setSide(params.side)
      devices.add(parent)
    }
    // If we are using child devices then we create a presence device and
    // all others are children of it.
    ((List<String>)params.types).each { String type ->
      if (type != sPRESENCE) {
        String driverType; driverType='null'
        String tt; tt=type
        if(tt==sUNDERBEDLIGHT) tt='underbed light'
        String childId = deviceId + "-" + tt.replaceAll(sSPACE, sBLK)
        //noinspection GroovyFallthrough
        switch (type) {
          case sOUTLET:
            driverType = "Switch"
            break
          case sHEAD:
          case sFOOT:
          case sFOOTWMR:
          case sUNDERBEDLIGHT:
            driverType = "Dimmer"
        }
        def newDevice = parent.createChildDevice(childId, "Generic Component ${driverType}",
            createDeviceLabel((String)settings.newDeviceName, type))
        if (newDevice) {
          devices.add(newDevice)
        }
      }
    }
  } else {
    ((List<String>)params.types).each { String type ->
      String tt; tt=type
      if(tt==sUNDERBEDLIGHT) tt='underbed light'
      String deviceId = "sleepnumber.${params.bedId}.${params.side}.${tt.replaceAll(' ', '_')}".toString()
      if (existingDevices.find{ it.data.vcId == deviceId }) {
        info "Not creating device ${deviceId}, it already exists"
      } else {
        String label = createDeviceLabel((String)settings.newDeviceName, type)
        def device
        if (container) {
          debug "Creating new child device ${deviceId} with label ${label} in container ${params.containerName}"
          container.appCreateDevice(label, DRIVER_NAME, NAMESPACE, deviceId)
          // #appCreateDevice doesn't return the device so find it
          device = container.childList().find({it.data.vcId == deviceId})
        } else {
          device = addChildDevice(NAMESPACE, DRIVER_NAME, deviceId, null, [label: label])
        }
        device.setStatus(params.presence)
        device.setBedId(params.bedId)
        device.setSide(params.side)
        device.setType(type)
        devices.add(device)
      }
    }
  }
  // Reset the bed info since we added more.
  initializeBedInfo()
  app.updateSetting "newDeviceName", [(sVL): sBLK, (sTYP): sTXT]
  settings.newDeviceName = sNL
  dynamicPage((sNM): "selectDevicePage") {
    section {
      String header
      header = "Created new devices"
      if ((Boolean)params.useChildDevices) {
        header += " using child devices"
      } else if ((Boolean)params.useContainer) {
        header += " in container ${params.containerName}"
      }
      header += ":"
      paragraph(header)
      String displayInfo
      displayInfo = "<ol>"
      devices.each { device ->
        displayInfo += "<li>"
        displayInfo += "${device.label}"
        if (!(Boolean)params.useChildDevices) {
          displayInfo += "<br>Bed ID: ${gtId(device)}"
          displayInfo += "<br>Side: ${gtSide(device)}"
          displayInfo += "<br>Type: ${gtType(device)}"
        }
        displayInfo += "</li>"
      }
      displayInfo += "</ol>"
      paragraph displayInfo
    }
    section {
      href "findBedPage", (sTIT): "Back to Bed List", (sDESC): sNL
    }
  }
}

def diagnosticsPage(Map params=null) {
  Map bedInfo = getBeds()
  dynamicPage((sNM): "diagnosticsPage") {
    ((List<Map>)bedInfo.beds).each { Map bed ->
      section("Bed: ${bed.bedId}") {
        String bedOutput
        bedOutput = "<ul>"
        bedOutput += "<li>Size: ${bed.size}"
        bedOutput += "<li>Dual Sleep: ${bed.dualSleep}"
        bedOutput += "<li>Components:"
        for (Map component in (List<Map>)bed.components) {
          bedOutput += "<ul>"
          bedOutput += "<li>Type: ${component.type}"
          bedOutput += "<li>Status: ${component.status}"
          bedOutput += "<li>Model: ${component.model}"
          bedOutput += "</ul>"
        }
        paragraph bedOutput
      }
    }
    //section("Dump of bedInfo") {
     // paragraph getMapDescStr(bedInfo)
    //}
    section("Send Requests") {
      input ("requestType", sENUM, (sTIT): "Request type", options: ["PUT", "GET"])
      input ("requestPath", sTXT, (sTIT): "Request path", (sDESC): "Full path including bed id if needed")
      input ("requestBody", sTXT, (sTIT): "Request Body in JSON")
      input ("requestQuery", sTXT, (sTIT): "Extra query key/value pairs in JSON")
      href ("diagnosticsPage", (sTIT): "Send request", (sDESC): sNL, params: [
        requestType: (String)settings.requestType,
        requestPath: (String)settings.requestPath,
        requestBody: (String)settings.requestBody,
        requestQuery: (String)settings.requestQuery
      ])
      if (params && params.requestPath && params.requestType) {
        Map body; body=null
        if (params.requestBody) {
          try {
            body = (Map)wparseJson((String)params.requestBody)
          } catch (e) {
            maybeLogError "${params.requestBody} : ${e}",e
          }
        }
        Map query; query=null
        if (params.requestQuery) {
          try {
            query = (Map)wparseJson((String)params.requestQuery)
          } catch (e) {
            maybeLogError "${params.requestQuery} : ${e}",e
          }
        }
        Map response = httpRequest((String)params.requestPath,
                                   (String)settings.requestType == "PUT" ? this.&put : this.&get,
                                   body,
                                   query,
                                   true)
        paragraph getMapDescStr(response)
      }
    }
    section("Authentication") {
      href "diagnosticsPage", title: "Clear session info", description: null, params: [clearSession: true]
      if (params && params.clearSession) {
        state.session = null
      }
    }
  }
}

@Field static final String sSPCSB7='      │'
@Field static final String sSPCSB6='     │'
@Field static final String sSPCS6 ='      '
@Field static final String sSPCS5 ='     '
@Field static final String sSPCST='┌─ '
@Field static final String sSPCSM='├─ '
@Field static final String sSPCSE='└─ '
@Field static final String sNWL='\n'
@Field static final String sDBNL='\n\n • '

@CompileStatic
static String spanStr(Boolean html,String s){ return html? span(s) : s }

@CompileStatic
static String doLineStrt(Integer level,List<Boolean>newLevel){
  String lineStrt; lineStrt=sNWL
  Boolean dB; dB=false
  Integer i
  for(i=iZ;i<level;i++){
    if(i+i1<level){
      if(!newLevel[i]){
        if(!dB){ lineStrt+=sSPCSB7; dB=true }
        else lineStrt+=sSPCSB6
      }else lineStrt+= !dB ? sSPCS6:sSPCS5
    }else lineStrt+= !dB ? sSPCS6:sSPCS5
  }
  return lineStrt
}

@CompileStatic
static String dumpListDesc(List data,Integer level,List<Boolean> lastLevel,String listLabel,Boolean html=false,Boolean reorder=true){
  String str; str=sBLK
  Integer cnt; cnt=i1
  List<Boolean> newLevel=lastLevel

  List list1=data?.collect{it}
  Integer sz=list1.size()
  for(Object par in list1){
    String lbl=listLabel+"[${cnt-i1}]".toString()
    if(par instanceof Map){
      Map newmap=[:]
      newmap[lbl]=(Map)par
      Boolean t1=cnt==sz
      newLevel[level]=t1
      str+=dumpMapDesc(newmap,level,newLevel,cnt,sz,!t1,html,reorder)
    }else if(par instanceof List || par instanceof ArrayList){
      Map newmap=[:]
      newmap[lbl]=par
      Boolean t1=cnt==sz
      newLevel[level]=t1
      str+=dumpMapDesc(newmap,level,newLevel,cnt,sz,!t1,html,reorder)
    }else{
      String lineStrt
      lineStrt=doLineStrt(level,lastLevel)
      lineStrt+=cnt==i1 && sz>i1 ? sSPCST:(cnt<sz ? sSPCSM:sSPCSE)
      str+=spanStr(html, lineStrt+lbl+": ${par} (${objType(par)})".toString() )
    }
    cnt+=i1
  }
  return str
}

@CompileStatic
static String dumpMapDesc(Map data,Integer level,List<Boolean> lastLevel,Integer listCnt=null,Integer listSz=null,Boolean listCall=false,Boolean html=false,Boolean reorder=true){
  String str; str=sBLK
  Integer cnt; cnt=i1
  Integer sz=data?.size()
  Map svMap,svLMap,newMap; svMap=[:]; svLMap=[:]; newMap=[:]
  for(par in data){
    String k=(String)par.key
    def v=par.value
    if(reorder && v instanceof Map){
      svMap+=[(k): v]
    }else if(reorder && (v instanceof List || v instanceof ArrayList)){
      svLMap+=[(k): v]
    }else newMap+=[(k):v]
  }
  newMap+=svMap+svLMap
  Integer lvlpls=level+i1
  for(par in newMap){
    String lineStrt
    List<Boolean> newLevel=lastLevel
    Boolean thisIsLast=cnt==sz && !listCall
    if(level>iZ)newLevel[(level-i1)]=thisIsLast
    Boolean theLast
    theLast=thisIsLast
    if(level==iZ)lineStrt=sDBNL
    else{
      theLast=theLast && thisIsLast
      lineStrt=doLineStrt(level,newLevel)
      if(listSz && listCnt && listCall)lineStrt+=listCnt==i1 && listSz>i1 ? sSPCST:(listCnt<listSz ? sSPCSM:sSPCSE)
      else lineStrt+=((cnt<sz || listCall) && !thisIsLast) ? sSPCSM:sSPCSE
    }
    String k=(String)par.key
    def v=par.value
    String objType=objType(v)
    if(v instanceof Map){
      str+=spanStr(html, lineStrt+"${k}: (${objType})".toString() )
      newLevel[lvlpls]=theLast
      str+=dumpMapDesc((Map)v,lvlpls,newLevel,null,null,false,html,reorder)
    }
    else if(v instanceof List || v instanceof ArrayList){
      str+=spanStr(html, lineStrt+"${k}: [${objType}]".toString() )
      newLevel[lvlpls]=theLast
      str+=dumpListDesc((List)v,lvlpls,newLevel,sBLK,html,reorder)
    }
    else{
      str+=spanStr(html, lineStrt+"${k}: (${v}) (${objType})".toString() )
    }
    cnt+=i1
  }
  return str
}

@CompileStatic
static String objType(obj){ return span(myObj(obj),sCLRORG) }

@CompileStatic
static String getMapDescStr(Map data,Boolean reorder=true){
  List<Boolean> lastLevel=[true]
  String str=dumpMapDesc(data,iZ,lastLevel,null,null,false,true,reorder)
  return str!=sBLK ? str:'No Data was returned'
}

static String myObj(obj){
  if(obj instanceof String)return 'String'
  else if(obj instanceof Map)return 'Map'
  else if(obj instanceof List)return 'List'
  else if(obj instanceof ArrayList)return 'ArrayList'
  else if(obj instanceof BigInteger)return 'BigInt'
  else if(obj instanceof Long)return 'Long'
  else if(obj instanceof Integer)return 'Int'
  else if(obj instanceof Boolean)return 'Bool'
  else if(obj instanceof BigDecimal)return 'BigDec'
  else if(obj instanceof Double)return 'Double'
  else if(obj instanceof Float)return 'Float'
  else if(obj instanceof Byte)return 'Byte'
  else if(obj instanceof com.hubitat.app.DeviceWrapper)return 'Device'
  else return 'unknown'
}





/**
 * Creates a virtual container with the given name and side
 */
def createContainer(String bedId, String containerName, String side) {
  def container
  container = ((List)getChildDevices()).find{ (String)it.typeName == "Virtual Container" &&  (String)it.label == containerName}
  if(!container) {
    debug "Creating container ${containerName}"
    try {
      container = addChildDevice("stephack", "Virtual Container", "${app.id}.${bedId}.${side}", null,
          [(sNM): containerName, label: containerName, completedSetup: true])
    } catch (e) {
      logError "Container device creation failed with error = ${e}",e
      return null
    }
  }
  return container
}

def getBedData() {
  Map responseData = getFamilyStatus()
  processBedData(responseData)
  return responseData
}

/**
 * Updates the bed devices with the given data.
 */
void processBedData(Map responseData) {
  if (!responseData || responseData.size() == iZ) {
    if(debugOk()) logDebug "Empty response data"
    return
  }
  //debug "Response data from SleepNumber: ${responseData}"
  // cache for foundation status per bed id so we don't have to run the api call N times
  Map foundationStatus = [:]
  Map footwarmingStatus = [:]
  Map<String,String> privacyStatus = [:]
  Map<String,Boolean> bedFailures = [:]
  Map<String,Boolean> loggedError = [:]
  Map sleepNumberFavorites = [:]
  Map<String,List> outletData = [:]
  Map<String,Map> underbedLightData = [:]
  def responsiveAir = [:]

  List<String> deviceTypes = getBedDeviceTypes()

  for (device in getBedDevices()) {
    String bedId = gtId(device)
    String bedSideStr = gtSide(device)
    debug("updating $device id: $bedId side: $bedSideStr")
    if (!outletData[bedId]) {
      outletData[bedId] = []
      underbedLightData[bedId] = [:]
    }

    Map bedInfo
    bedInfo=(Map)gtSt('bedInfo')

    for (bed in (List<Map>)responseData.beds) {
      String bedId1 = bed.bedId
      Map bedInfoBed
      bedInfoBed= bedInfo ? (Map)bedInfo[bedId1] : null
      // Make sure the various bed state info is set up so we can use it later.
      if (!bedInfoBed || !bedInfoBed.components) {
        warn "state.bedInfo somehow lost, re-caching it"
        initializeBedInfo()
      }
      bedInfo=(Map)gtSt('bedInfo')
      bedInfoBed=  bedInfo ? (Map)bedInfo[bedId1] : null
      if (bedId == bedId1) {
        debug("matched $device id: $bedId side: $bedSideStr")
        if (!bedFailures[bedId] && !privacyStatus[bedId]) {
          privacyStatus[bedId] = getPrivacyMode(bedId)
          if (!privacyStatus[bedId]) { bedFailures[bedId] = true }
        }
        // Note that it is possible to have a mattress without the base.  Prior, this used the presence of "Base"
        // in the bed status but it turns out SleepNumber doesn't always include that even when the base is
        // adjustable.  So instead, this relies on the devices the user created.
        if (!bedFailures[bedId]
            && !foundationStatus[bedId]
            && (deviceTypes.contains(sHEAD) || deviceTypes.contains(sFOOT))
          ) {
          foundationStatus[bedId] = getFoundationStatus(bedId, bedSideStr)
          if (!foundationStatus[bedId]) { bedFailures[bedId] = true }
        }
        // So far, the presence of "Warming" in the bed status indicates a foot warmer.
        // Only try to update the warming state if the bed actually has it
        // and there's a device for it.
        if (
            (deviceTypes.contains(sFOOTWMR) || deviceTypes.contains("footwarmer"))
            && !bedFailures[bedId]
            && !footwarmingStatus[bedId]
            && ((List<String>)bedInfoBed?.components)?.contains("Warming")
          ) {
          footwarmingStatus[bedId] = getFootWarmingStatus(bedId)
          if (!footwarmingStatus[bedId]) { bedFailures[bedId] = true }
        }
        // If there's underbed lighting or outlets then poll for that data as well.  Don't poll
        // otherwise since it's just another network request and may be unwanted.
        // RIGHT_NIGHT_STAND = 1 LEFT_NIGHT_STAND = 2 RIGHT_NIGHT_LIGHT = 3 LEFT_NIGHT_LIGHT = 4
        if (!bedFailures[bedId] && deviceTypes.contains(sUNDERBEDLIGHT)) {
          determineUnderbedLightSetup(bedId)
          if (!outletData[bedId][i3]) {
            outletData[bedId][i3] = getOutletState(bedId,i3)
            if (!outletData[bedId][i3]) { bedFailures[bedId] = true }
          }
          if (!bedFailures[bedId] && !underbedLightData[bedId]) {
            underbedLightData[bedId] = getUnderbedLightState(bedId)
            if (!underbedLightData[bedId]) { bedFailures[bedId] = true }
            else {
              Map brightnessData = getUnderbedLightBrightness(bedId)
              if (!brightnessData) { bedFailures[bedId] = true }
              else { underbedLightData[bedId] += brightnessData }
            }
          }
          if (((List)bedInfoBed?.outlets)?.size() > i1) {
            if (!bedFailures[bedId] && !outletData[bedId][i4]) {
              outletData[bedId][i4] = getOutletState(bedId, i4)
              if (!outletData[bedId][i4]) { bedFailures[bedId] = true }
            }
          } else {
            outletData[bedId1][i4] = outletData[bedId1][i3]
          }
        }

        // RIGHT_NIGHT_STAND = 1 LEFT_NIGHT_STAND = 2 RIGHT_NIGHT_LIGHT = 3 LEFT_NIGHT_LIGHT = 4
        if (!bedFailures[bedId] && deviceTypes.contains(sOUTLET)) {
          if (!outletData[bedId][i1]) {
            outletData[bedId][i1] = getOutletState(bedId, i1)
            if (!outletData[bedId][i1]) { bedFailures[bedId] = true }
            else {
              outletData[bedId][i2] = getOutletState(bedId, i2)
              if (!outletData[bedId][i2]) { bedFailures[bedId] = true }
            }
          }
        }

        Map bedSide = bedSideStr == sRIGHT ? (Map)bed.rightSide : (Map)bed.leftSide
        device.setPresence((Boolean)bedSide.isInBed)
        Map statusMap
        statusMap = [
          sleepNumber: bedSide.sleepNumber,
          privacyMode: privacyStatus[bedId],
        ]
        if (underbedLightData[bedId]) {
          Integer outletNumber = bedSideStr == sLEFT ? i3 : i4
          Map outletDataBedOut = outletData[bedId][outletNumber]
          String bstate = underbedLightData[bedId]?.enableAuto ? "Auto" : outletDataBedOut?.setting == i1 ? sSTON : sSTOFF
          String timer = bstate == "Auto" ? "Not set" : outletDataBedOut?.timer ?: "Forever"
          def brightness = underbedLightData[bedId]?."fs${bedSideStr}UnderbedLightPWM"
          statusMap += [
            underbedLightState: bstate,
            underbedLightTimer: timer,
            underbedLightBrightness: brightness,
          ]
        }

        if (outletData[bedId] && outletData[bedId][i1]) {
          Integer outletNumber = bedSideStr == sLEFT ? i1 : i2
          Map outletDataBedOut = outletData[bedId][outletNumber]
          statusMap += [
            outletState: outletDataBedOut?.setting == i1 ? sSTON : sSTOFF
          ]
        }
        // Check for valid foundation status and footwarming status data before trying to use it
        // as it's possible the HTTP calls failed.
        if (foundationStatus[bedId]) {
	        // Positions are in hex so convert to a decimal
          Integer headPosition = convertHexToNumber((String)foundationStatus[bedId]."fs${bedSideStr}HeadPosition")
          Integer footPosition = convertHexToNumber((String)foundationStatus[bedId]."fs${bedSideStr}FootPosition")
          def bedPreset = foundationStatus[bedId]."fsCurrentPositionPreset${bedSideStr}"
          // There's also a MSB timer but not sure when that gets set.  Least significant bit seems used for all valid times.
          Integer positionTimer = convertHexToNumber((String)foundationStatus[bedId]."fs${bedSideStr}PositionTimerLSB")
          statusMap += [
            headPosition: headPosition,
            footPosition:  footPosition,
            positionPreset: bedPreset,
            positionPresetTimer: foundationStatus[bedId]."fsTimerPositionPreset${bedSideStr}",
            positionTimer: positionTimer
          ]
        } else if (!loggedError[bedId]) {
          if(debugOk()) logDebug "Not updating foundation state, " + (bedFailures[bedId] ? "error making requests" : "no data")
        }
        if (footwarmingStatus[bedId]) {
          statusMap += [
            footWarmingTemp: footwarmingStatus[bedId]."footWarmingStatus${bedSideStr}",
            footWarmingTimer: footwarmingStatus[bedId]."footWarmingTimer${bedSideStr}",
          ]
        } else if (!loggedError[bedId]) {
          if(debugOk()) logDebug "Not updating footwarming state, " + (bedFailures[bedId] ? "error making requests" : "no data")
        }
        if (!sleepNumberFavorites[bedId]) {
          sleepNumberFavorites[bedId] = getSleepNumberFavorite(bedId)
        }
        Integer favorite = ((Map)sleepNumberFavorites[bedId]).get("sleepNumberFavorite" + bedSideStr, -1)
        if (favorite >= iZ) {
          statusMap += [
            sleepNumberFavorite: favorite
          ]
        }
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
        break
      }
    }
  }
  if (bedFailures.size() == iZ) {
    state.status = "Online"
  }
  if(foundationStatus || footwarmingStatus) debug "Cached data: ${foundationStatus}\n${footwarmingStatus}"
}

@CompileStatic
Integer convertHexToNumber(String value) {
  if (value == sBLK || value == sNL) return iZ
  try {
    return Integer.parseInt(value, 16)
  } catch (Exception e) {
    logError "Failed to convert non-numeric value ${value}: ${e}",e
    return null
  }
}





@Field volatile static Map<String, Map> sleepMapFLD      = [:]

@CompileStatic
Map getBeds(Boolean lazy=false) {
  String myId=gtAid()
  Integer lastUpd = getLastTsValSecs("lastBedDataUpdDt")
  if(sleepMapFLD[myId] && (!lazy && lastUpd < 7200) || (lazy && lastUpd <= 14400)) {
    debug "Getting CACHED information for all beds ${sleepMapFLD[myId]}"
    return sleepMapFLD[myId]
  }
  debug "Getting information for all beds"
  Map res= httpRequest("/rest/bed")
  if(res){
    sleepMapFLD[myId]=res
    updTsVal("lastBedDataUpdDt")
  }
  return res
}

@Field volatile static Map<String, Map> familyMapFLD      = [:]

@CompileStatic
Map getFamilyStatus(Boolean lazy=false) {
  String myId=gtAid()
  Integer lastUpd = getLastTsValSecs("lastFamilyDataUpdDt")
  if(familyMapFLD[myId] && (!lazy && lastUpd < 180) || (lazy && lastUpd <= 550)) {
    if(debugOk()) logDebug "Getting CACHED family status ${familyMapFLD[myId]}"
    return familyMapFLD[myId]
  }
  if(debugOk()) logDebug "Getting family status"
  Map res= httpRequest("/rest/bed/familyStatus")
  if(res){
    familyMapFLD[myId]=res
    updTsVal("lastFamilyDataUpdDt")
  }
  return res
}

Map getFoundationStatus(String bedId, String currentSide) {
  debug "Getting Foundation Status for ${bedId} / ${currentSide}"
  return httpRequest("/rest/bed/${bedId}/foundation/status")
}

Map getFootWarmingStatus(String bedId) {
  debug "Getting Foot Warming Status for ${bedId}"
  return httpRequest("/rest/bed/${bedId}/foundation/footwarming")
}

def getResponsiveAirStatus(String bedId) {
  debug "Getting responsive air status for ${bedId}"
  return httpRequest("/rest/bed/${bedId}/responsiveAir")
}

def setResponsiveAirState(Boolean state, String devId) {
  def device = gtBedDev(devId)
  if (!device) return
  Map body = [:]
  String side = gtSide(device)
  debug "Setting responsive air state ${side} to ${state}"
  if (side.toLowerCase().equals("right")) {
    body << [
            rightSideEnabled: state
    ]
  } else {
    body << [
            leftSideEnabled: state
    ]
  }
  httpRequestQueue(5, path: "/rest/bed/${gtId(device)}/responsiveAir",
          body: body, runAfter: "refreshChildDevices")
}

def gtBedDev(String devId){
  def device = getBedDevices().find { devId == (String)it.deviceNetworkId }
  if (!device) {
    logError "Bed device with id ${devId} is not a valid child"
    return null
  }
  return device
}

/**
 * Params must be a Map containing keys actuator and position.
 * The side is derived from the specified device.
 */
void setFoundationAdjustment(Map params, String devId) {
  def device = gtBedDev(devId)
  if (!device) return

  String actu= (String)params?.actuator
  Integer pos= (Integer)params?.position
  if (!actu || pos == null) {
    logError "Missing param values, actuator and position are required"
    return
  }
  if (!VALID_ACTUATORS.contains(actu)) {
    logError "Invalid actuator ${actu}, valid values are ${VALID_ACTUATORS}"
    return
  }
  Map body = [
    speed: iZ, // 1 == slow, 0=fast
    actuator: actu,
    side: gtSide(device)[iZ],
    position: pos // 0-100
  ]
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // The timing appears to be linear which means it's 0.35 seconds per level adjusted for the head and 0.18
  // for the foot.
  Integer currentPosition = actu == "H" ? device.currentValue("headPosition") : device.currentValue("footPosition")
  Integer positionDelta = Math.abs(pos - currentPosition)
  Float movementDuration = actu == "H" ? 0.35 : 0.18
  Long waitTime = Math.round(movementDuration * positionDelta).toInteger() + i1
  httpRequestQueue(waitTime, path: "/rest/bed/${gtId(device)}/foundation/adjustment/micro",
      body: body, runAfter: "refreshChildDevices")
}

/**
 * Params must be a Map containing keys temp and timer.
 * The side is derived from the specified device.
 */
void setFootWarmingState(Map params, String devId) {
  def device = gtBedDev(devId)
  if (!device) return
  Integer ptemp = (Integer)params?.temp
  Integer ptimer = (Integer)params?.timer
  if (ptemp == null || ptimer == null) {
    logError "Missing param values, temp and timer are required"
    return
  }
  if (!VALID_WARMING_TIMES.contains(ptimer)) {
    logError "Invalid warming time ${ptimer}, valid values are ${VALID_WARMING_TIMES}"
    return
  }
  if (!VALID_WARMING_TEMPS.contains(ptemp)) {
    logError "Invalid warming temp ${ptemp}, valid values are ${VALID_WARMING_TEMPS}"
    return
  }
  String sid = gtSide(device)
  Map body = [
          ("footWarmingTemp${sid}".toString()): ptemp,
          ("footWarmingTimer${sid}".toString()): ptimer
  ]
  // Shouldn't take too long for the bed to reflect the new state, wait 5s just to be safe
  httpRequestQueue(5L, path: "/rest/bed/${gtId(device)}/foundation/footwarming",
      body: body, runAfter: "refreshChildDevices")
}

/**
 * Params must be a map containing keys preset and timer.
 * The side is derived from the specified device.
 */
void setFoundationTimer(Map params, String devId) {
  def device = gtBedDev(devId)
  if (!device) return
  Integer ppreset = (Integer)params?.preset
  Integer ptimer = (Integer)params?.timer
  if (ppreset == null || ptimer == null) {
    logError "Missing param values, preset and timer are required"
    return
  }
  if (!VALID_PRESETS.contains(ppreset)) {
    logError "Invalid preset ${ppreset}, valid values are ${VALID_PRESETS}"
    return
  }
  if (!VALID_PRESET_TIMES.contains(ptimer)) {
    logError "Invalid timer ${ptimer}, valid values are ${VALID_PRESET_TIMES}"
    return
  }
  Map body = [
    side: gtSide(device)[iZ],
    positionPreset: ppreset,
    positionTimer: ptimer
  ]
  vhttpRequest("/rest/bed/${gtId(device)}/foundation/adjustment", this.&put, body)
  // Shouldn't take too long for the bed to reflect the new state, wait 5s just to be safe
  wrunIn(5L, "refreshChildDevices")
}

/**
 * The side is derived from the specified device.
 */
void setFoundationPreset(Integer preset, String devId) {
  def device = gtBedDev(devId)
  if (!device) return
  if (!VALID_PRESETS.contains(preset)) {
    logError "Invalid preset ${preset}, valid values are ${VALID_PRESETS}"
    return
  }
  Map body = [
    speed: iZ,
    preset : preset,
    side: gtSide(device)[iZ]
  ]
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // Rather than attempt to derive the preset relative to the current state so we can compute
  // the time (as we do for adjustment), we just use the maximum.
  httpRequestQueue(35L, path: "/rest/bed/${gtId(device)}/foundation/preset",
      body: body, runAfter: "refreshChildDevices")
}

void stopFoundationMovement(Map ignored, String devId) {
  def device = gtBedDev(devId)
  if (!device) return
  Map body = [
    massageMotion: i1, // iZ,
    headMotion: i1,
    footMotion: i1,
    side: gtSide(device)[iZ]
  ]
  vhttpRequest("/rest/bed/${gtId(device)}/foundation/motion", this.&put, body)
  remTsVal("lastFamilyDataUpdDt")
  wrunIn(5L, "refreshChildDevices")
}

/**
 * The side is derived from the specified device.
 */
void setSleepNumber(number, String devId) {
  def device = gtBedDev(devId)
  if (!device) return

  String id= gtId(device)
  Map body = [
    bedId: id,
    sleepNumber: number,
    side: gtSide(device)[iZ]
  ]
  // Not sure how long it takes to inflate or deflate so just wait 20s
  httpRequestQueue(20L, path: "/rest/bed/${id}/sleepNumber",
      body: body, runAfter: "refreshChildDevices")
}

@Field volatile static Map<String, Map> privacyMapFLD      = [:]

@CompileStatic
String getPrivacyMode(String bedId, Boolean lazy=false) {
  Integer lastUpd = getLastTsValSecs("lastPrivacyDataUpdDt")
  if(privacyMapFLD[bedId] && (!lazy && lastUpd < 7200) || (lazy && lastUpd <= 14400)) {
    if(debugOk()) logDebug "Getting CACHED Privacy Mode for ${bedId} ${privacyMapFLD[bedId]}"
    return (String)privacyMapFLD[bedId].pauseMode
  }
  if(debugOk()) logDebug "Getting Privacy Mode for ${bedId}"
  Map res= httpRequest("/rest/bed/${bedId}/pauseMode", this.&get)
  if(res){
    privacyMapFLD[bedId]=res
    updTsVal("lastPrivacyDataUpdDt")
  }
  return (String)res?.pauseMode
}

void setPrivacyMode(Boolean mode, String devId) {
  def device = gtBedDev(devId)
  if (!device) return
  String pauseMode = mode ? sON : sOFF
  // Cloud request so no need to queue.
  httpRequest("/rest/bed/${gtId(device)}/pauseMode", this.&put, null, [mode: pauseMode])
  remTsVal("lastPrivacyDataUpdDt")
  remTsVal("lastFamilyDataUpdDt")
  remTsVal("lastSleeperDataUpdDt")
  wrunIn(2L, "refreshChildDevices")
}

String gtId(dev){ return (String)((Map)dev.getState()).bedId }
String gtSide(dev){ return (String)((Map)dev.getState()).side }
String gtType(dev){ return (String)((Map)dev.getState()).type }

@Field volatile static Map<String, Map> sleepNumMapFLD      = [:]

@CompileStatic
Map getSleepNumberFavorite(String bedId, Boolean lazy=false) {
  Integer lastUpd = getLastTsValSecs("lastSleepNumDataUpdDt")
  if(sleepNumMapFLD[bedId] && (!lazy && lastUpd < 7200) || (lazy && lastUpd <= 14400)) {
    debug "Getting CACHED Sleep Number Favorites ${sleepNumMapFLD[bedId]}"
    return sleepNumMapFLD[bedId]
  }
  debug "Getting Sleep Number Favorites"
  Map res= httpRequest("/rest/bed/${bedId}/sleepNumberFavorite", this.&get)
  if(res){
    sleepNumMapFLD[bedId]=res
    updTsVal("lastSleepNumDataUpdDt")
  }
  return res
}

// set sleep number to current favorite
void setSleepNumberFavorite(String ignored, String devId) {
  def device = gtBedDev(devId)
  if (!device) return
  // Get the favorite for the device first, the most recent poll should be accurate
  // enough.
  Integer favorite = device.currentValue("sleepNumberFavorite")
  String sid=gtSide(device)
  debug "sleep number favorite for ${sid} is ${favorite}"
  if (!favorite || favorite < iZ) {
    logError "Unable to determine sleep number favorite for side ${sid}"
    return
  }
  if (device.currentValue("sleepNumber") == favorite) {
    debug "Already at favorite"
    return
  }
  setSleepNumber(favorite, devId)
}

// update the sleep number favorite
void updateSleepNumberFavorite(Integer number, String devId) {
  def device = gtBedDev(devId)
  if (!device) return

  Integer dfavorite = (Math.round(number/5)*5).toInteger()
  Integer favorite = device.currentValue("sleepNumberFavorite")
  String sid=gtSide(device)
  debug "update sleep number favorite for ${sid} to ${dfavorite}, is ${favorite}"

  if (dfavorite && dfavorite > iZ && dfavorite <= 100) {
    if (dfavorite == favorite) {
      debug "Already at favorite"
      return
    }
    // side "R" or "L"
    // setting 0-100 (rounds to nearest multiple of 5)
    String id= gtId(device)
    Map body = [
      bedId: id,
      sleepNumberFavorite: dfavorite,
      side: sid[iZ]
    ]

    httpRequestQueue(2L, path: "/rest/bed/${id}/sleepNumberFavorite", body: body /*, runAfter: "refreshChildDevices"*/)
    setSleepNumber(dfavorite, devId)
  } else {
    logError "Unable to update sleep number favorite for side ${sid} ${number}"
  }
  remTsVal("lastSleepNumDataUpdDt")
}
// RIGHT_NIGHT_STAND = 1
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

void setFoundationMassage(Integer ifootspeed, Integer iheadspeed, Integer itimer=iZ, Integer mode=iZ, String devId){
  def device = gtBedDev(devId)
  if (!device) return

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
  String sid=gtSide(device)
  String id= gtId(device)
  Map body = [
    footMassageMotor: footspeed,
    headMassageMotor: headspeed,
    massageTimer: timer,
    massageWaveMode: mode,
    side: sid[iZ]
  ]
  httpRequestQueue(1L, path: "/rest/bed/${id}/foundation/adjustment",
        body: body, runAfter: "refreshChildDevices")
}

Map getOutletState(String bedId, Integer outlet) {
  return httpRequest("/rest/bed/${bedId}/foundation/outlet",
        this.&get, null, [outletId: outlet])
}

void setOutletState(String outletState, String devId) {
  def device = gtBedDev(devId)
  if (!device) return

  if (!outletState) {
    logError "Missing outletState"
    return
  }
  Integer outletNum = gtSide(device) == sLEFT ? i1 : i2
  setOutletState(gtId(device), outletNum, outletState)
}

/**
 * Sets the state of the given outlet.
 * @param bedId: the bed id
 * @param outletId: 1-4
 * @param state: on or off
 * @param timer: a valid minute duration (for outlets 3 and 4 only)
 * Timer is the only optional parameter.
 */
void setOutletState(String bedId, Integer outletId, String ioutletState, Integer itimer = null) {
  String outletState; outletState=ioutletState
  Integer timer; timer=itimer
  if (!bedId || !outletId || !outletState) {
    logError "Not all required arguments present"
    return
  }

  if (timer && !VALID_LIGHT_TIMES.contains(timer)) {
    logError "Invalid underbed light timer ${timer}.  Valid values are ${VALID_LIGHT_TIMES}"
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
  httpRequestQueue(1L, path: "/rest/bed/${bedId}/foundation/outlet",
      body: body, runAfter: "refreshChildDevices")
}

Map getUnderbedLightState(String bedId) {
  httpRequest("/rest/bed/${bedId}/foundation/underbedLight", this.&get)
}

Map getFoundationSystem(String bedId) {
  return httpRequest("/rest/bed/${bedId}/foundation/system", this.&get)
}

Map getUnderbedLightBrightness(String bedId) {
  determineUnderbedLightSetup(bedId)
  Map brightness = getFoundationSystem(bedId)
  if (brightness && ((List)((Map)state.bedInfo[bedId]).outlets).size() <= i1) {
    // Strangely if there's only one light then the `right` side is the set value
    // so just set them both the same.
    brightness.fsLeftUnderbedLightPWM = brightness.fsRightUnderbedLightPWM
  }
  return brightness
}

/**
 * Determines how many underbed light exists and sets up state.
 */
void determineUnderbedLightSetup(String bedId) {
  Map<String,Map> bdinfo= (Map<String,Map>)state.bedInfo
  if (bdinfo[bedId].outlets == null) {
    debug "Determining underbed lighting outlets for ${bedId}"
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
    bdinfo[bedId].outlets=outlets
    state.bedInfo=bdinfo
  }
}

/**
 * Sets the underbed lighting per given params.
 * If only timer is given, state is assumed to be `on`.
 * If the foundation has outlet 3 and 4 then the bed side
 * will be used to determine which to enable.
 * The params map must include:
 * state: on, off, auto
 * And may include:
 * timer: valid minute duration
 * brightness: low, medium, high
 */
void setUnderbedLightState(Map params, String devId) {
  def device = gtBedDev(devId)
  if (!device) return

  if (!params.state) {
    logError "Missing param state"
    return
  }

  String ps
  ps= ((String)params.state).toLowerCase()
  Integer pt,pb
  pt= (Integer)params.timer
  pb= (Integer)params.brightness
  //params.state = params.state.toLowerCase()

  // A timer with a state of auto makes no sense, choose to honor state vs. timer.
  if (ps == "auto") { pt = iZ }
  if (pt) { ps = sON }

  if (pb && !VALID_LIGHT_BRIGHTNESS.contains(pb)) {
    logError "Invalid underbed light brightness ${pb}. Valid values are ${VALID_LIGHT_BRIGHTNESS}"
    return
  }

  // First set the light state.
  Map body
  body = [
    enableAuto: ps == "auto"
  ]
  String id=gtId(device)
  httpRequest("/rest/bed/${id}/foundation/underbedLight", this.&put, body)

  determineUnderbedLightSetup(id)
  Integer rightBrightness, leftBrightness
  rightBrightness = pb
  leftBrightness = pb
  Integer outletNum
  outletNum = i3
  if (((List)((Map)state.bedInfo[id]).outlets).size() > i1) {
    // Two outlets so set the side corresponding to the device rather than
    // defaulting to 3 (which should be a single light)
    if (gtSide(device) == sLEFT) {
      outletNum = i3
      rightBrightness = null
      leftBrightness = pb
    } else {
      outletNum = i4
      rightBrightness = pb
      leftBrightness = null
    }
  }
  setOutletState(id, outletNum, ps == "auto" ? sOFF : ps, pt)

  // If brightness was given then set it.
  if (pb) {
    body = [
      rightUnderbedLightPWM: rightBrightness,
      leftUnderbedLightPWM: leftBrightness
    ]
    //Map a=httpRequest("/rest/bed/${id}/foundation/system", this.&put, body)
    httpRequestQueue(1L, path: "/rest/bed/${id}/foundation/system",
            body: body, runAfter: "refreshChildDevices")
  }
}

@Field volatile static Map<String, Map> sleepersMapFLD      = [:]

@CompileStatic
Map getSleepers(Boolean lazy=false) {
  Integer lastUpd = getLastTsValSecs("lastSleeperDataUpdDt")
  String myId=gtAid()
  if(sleepersMapFLD[myId] && (!lazy && lastUpd < 7200) || (lazy && lastUpd <= 14400)) {
    debug "Getting CACHED Sleepers ${sleepersMapFLD[myId]}"
    return sleepersMapFLD[myId]
  }
  debug "Getting Sleepers"
  Map res = httpRequest("/rest/sleeper", this.&get)
  if(res){
    sleepersMapFLD[myId]=res
    updTsVal("lastSleeperDataUpdDt")
  }
  return res
}

/**
 *  called by child device to get summary sleep data
 */
Map getSleepData(Map ignored, String devId) {
  def device = gtBedDev(devId)
  if (!device) return null

  String bedId = gtId(device)
  Map ids = [:]

  // We need a sleeper id for the side in order to look up sleep data.
  // Get sleeper to get list of sleeper ids
  Map sleepers = getSleepers()

  ((List<Map>)sleepers.sleepers).each() { sleeper ->
    if ((String)sleeper.bedId == bedId) {
      String side; side=sNL
      switch (sleeper.side) {
        case iZ:
          side = sLEFT
          break
        case i1:
          side = sRIGHT
          break
        default:
          warn "Unknown sleeper info: ${sleeper}"
      }
      if (side) {
        ids[side] = sleeper.sleeperId
      }
    }
  }

  String sid=gtSide(device)
  debug "Getting sleep data for ${ids[sid]}"
  // Interval can be W1 for a week, D1 for a day and M1 for a month.
  return httpRequest("/rest/sleepData", this.&get, null, [
      interval: "D1",
      sleeper: ids[sid],
      includeSlices: false,
      date: new Date().format("yyyy-MM-dd'T'HH:mm:ss")
  ])
}

void loginAws() {
  debug "Logging in"
  if (state.session?.refreshToken) {
    state.session.accessToken = null
    try {
      JSONObject jsonBody = new JSONObject()
      jsonBody.put("RefreshToken", (String)state.session.refreshToken)
      jsonBody.put("ClientID", LOGIN_CLIENT_ID)
      Map params = [
              uri: LOGIN_URL + "/Prod/v1/token",
              requestContentType: "application/json",
              contentType: "application/json",
              headers: [
                      "Host": LOGIN_HOST,
                      "User-Agent": USER_AGENT,
              ],
              body: jsonBody.toString(),
              timeout: 20
      ]
      httpPut(params) { response ->
        if (response.success) {
          debug "refresh Success: (${response.status}) ${response.data}"
          state.session.accessToken = response.data.data.AccessToken
          // Refresh the access token 1 minute before it expires
          runIn((response.data.data.ExpiresIn - 60), loginAws)
        } else {
          // If there's a failure here then purge all session data to force clean slate
          state.session = null
          maybeLogError "login Failure refreshing Token: (${response.status}) ${response.data}"
          state.status = "Login Error"
        }
      }
    } catch (Exception e) {
      // If there's a failure here then purge all session data to force clean slate
      state.session = null
      maybeLogError "login Error: ${e}"
      state.status = "Login Error"
    }
  } else {
    state.session = null
    try {
      JSONObject jsonBody = new JSONObject()
      jsonBody.put("Email", (String)settings.login)
      jsonBody.put("Password", (String)settings.password)
      jsonBody.put("ClientID", LOGIN_CLIENT_ID)
      Map params = [
              uri: LOGIN_URL + "/Prod/v1/token",
              headers: [
                      "Host": LOGIN_HOST,
                      "User-Agent": USER_AGENT,
              ],
              body: jsonBody.toString(),
              timeout: 20
      ]
      httpPostJson(params) { response ->
        if (response.success) {
          debug "login Success: (${response.status}) ${response.data}"
          state.session = [:]
          state.session.accessToken = response.data.data.AccessToken
          state.session.refreshToken = response.data.data.RefreshToken
          // Refresh the access token 1 minute before it expires
          runIn((response.data.data.ExpiresIn - 60), loginAws)
          // Get cookies since this is all new state
          loginCookie()
        } else {
          maybeLogError "login Failure getting Token: (${response.status}) ${response.data}"
          state.status = "Login Error"
        }
      }
    } catch (Exception e) {
      maybeLogError "login Error: ${e}"
      state.status = "Login Error"
    }
  }
}

void loginCookie() {
  state.session.cookies = null
  try {
    debug "Getting cookie"
    Map params = [
            uri: API_URL + "/rest/account",
            headers: [
                    "Host": API_HOST,
                    "User-Agent": USER_AGENT,
                    "Authorization": state.session.accessToken,
            ],
            timeout: 20
    ]
    httpGet(params) { response ->
      if (response.success) {
        String expiration; expiration = null
        //Map sess=[:]
        //sess.key = response.data.key
        //sess.cookies = sBLK
        response.getHeaders("Set-Cookie").each {
          String[] cookieInfo = ((String)it.value).split(";")
          state.session.cookies = (String)state.session.cookies + cookieInfo[iZ] + ";"
          // find the expires value if it exists
          if (!expiration) {
            for (String cookie in cookieInfo) {
              if (cookie.contains("Expires=")) {
                expiration = cookie.split("=")[i1]
              }
            }
          }
        }
        Date refreshDate
        if (expiration == sNL) {
          maybeLogError "No expiration for any cookie found in response: " + "${response.getHeaders("Set-Cookie")}"
          refreshDate = new Date() + 1
        } else {
          refreshDate = wtoDateTime(LocalDateTime.parse(expiration,
                  DateTimeFormatter.RFC_1123_DATE_TIME).minusDays(1L).toString() + "Z")
        }
        runOnce(refreshDate, loginCookie)
      } else {
        maybeLogError "login Failure getting Cookie: (${response.status}) ${response.data}"
        state.status = "Login Error"
      }
    }
  } catch (Exception e) {
    maybeLogError "loginCookie Error: ${e}"
    state.status = "Login Error"
  }
}

void loginOld() {
  debug "Logging in"
  state.session = null
  try {
    JSONObject jsonBody = new JSONObject()
    jsonBody.put("login", (String)settings.login)
    jsonBody.put("password", (String)settings.password)
    Map params = [
      uri: API_URL + "/rest/login",
      requestContentType: "application/json",
      contentType: "application/json",	
      headers: [
        "Host": API_HOST,
        "User-Agent": USER_AGENT,
        "DNT": "1",
      ],
      body: jsonBody.toString(),
      timeout: i20
    ]
    httpPut(params) { response ->
      if (response.success) {
        debug "login Success: (${response.status}) ${response.data}"
        Map sess=[:]
        sess.key = response.data.key
        sess.cookies = sBLK
        response.getHeaders("Set-Cookie").each {
          sess.cookies = sess.cookies + ((String)it.value).split(";")[iZ] + ";"
        }
        state.session = sess
      } else {
        maybeLogError "login Failure: (${response.status}) ${response.data}"
        state.status = sLOGINERR
      }
    }
  } catch (Exception e) {
    maybeLogError "login Error: ${e}",e
    state.status = sLOGINERR
  }
}

@Field static final String sLOGINERR = 'Login Error'

void login() {
  if ((Boolean)settings.useAwsOAuth) {
    loginAws()
  } else {
    loginOld()
  }
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
void httpRequestQueue(Map args, Long duration) {
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
  if (releaseLock) mutex.release()
  if (requestQueue.isEmpty()) return
  if(!lastLockTime) lastLockTime=wnow()
  // Get the oldest request in the queue to run.
  try {
    if (!mutex.tryAcquire()) {
      // If we can't obtain the lock it means one of two things:
      // 1. There's an existing operation and we should rightly skip.  In this case,
      //    the last thing the method does is re-run itself so this will clear itself up.
      // 2. There's an unintended failure which has lead to a failed lock release.  We detect
      //    this by checking the last time the lock was held and releasing the mutex if it's
      //    been too long.
      if ((wnow() - lastLockTime) > 120000 /* 2 minutes */) {
        // Due to potential race setting and reading the lock time,
        // wait 2s and check again before breaking it
        wpauseExecution(2000L)
        if ((wnow() - lastLockTime) > 120000 /* 2 minutes */) {
          warn "HTTP queue lock was held for more than 2 minutes, forcing release"
          mutex.release()
          // In this case we should re-run.
          handleRequestQueue()
        }
      }
      return
    }
    lastLockTime = wnow()
    Map request = (Map)requestQueue.poll()
    if(request){
      vhttpRequest((String)request.path, this.&put, (Map)request.body, (Map)request.query) // this can take a long time

      Long rd= (Long)request.duration
      // Let this operation complete then process more requests or release the lock
      wrunInMillis(Math.round(rd * 1000.0D), "handleRequestQueue", [data: true])

      // If there was something to run after this then set that up as well.
      String ra= (String)request.runAfter
      if (ra) {
        remTsVal("lastFamilyDataUpdDt")
        wrunIn(rd, ra, [overwrite:false])
      }
    }
  } catch(e) {
    maybeLogError "Failed to run HTTP queue: ${e}",e
    mutex.release()
  }
}

void vhttpRequest(String path, Closure method = this.&get, Map body = null, Map query = null, Boolean alreadyTriedRequest = false) {
  Map a=httpRequest(path,method,body,query,alreadyTriedRequest)
}


Map httpRequest(String path, Closure method = this.&get, Map body = null, Map query = null, Boolean alreadyTriedRequest = false) {
  Map result; result = [:]
  Map sess=(Map)state.session
  Boolean useAwsO= (Boolean)settings.useAwsOAuth
  Boolean loginState = useAwsO ? !sess || !sess.accessToken : !sess || !sess.key
  if (loginState) {
    if (alreadyTriedRequest) {
      maybeLogError "Already attempted login but still no session key, giving up"
      return result
    } else {
      login()
      if (useAwsO) {
        loginAws()
      } else {
        login()
      }
      return httpRequest(path, method, body, query, true)
    }
  }
  String payload = body ? new JsonBuilder(body).toString() : null
  Map queryString; queryString = useAwsO ? new HashMap() : [_k: sess.key]
  if (query) {
    queryString = queryString + query
  }
  Map statusParams = [
    uri: API_URL,
    path: path,
    requestContentType: "application/json",
    contentType: "application/json",
    headers: [
      "Host": API_HOST,
      "User-Agent": USER_AGENT,
      "Cookie": sess?.cookies,
      "DNT": "1",
      "Accept-Version": "4.4.1",
      "X-App-Version": "4.4.1",
    ],
    query: queryString,
    body: payload,
    timeout: i20
  ]
  if (useAwsO) {
    statusParams.headers["Authorization"] = sess.accessToken
  }
  if(debugOk()){
    String s; s= "Sending request for ${path} with query ${queryString}"
    if (payload) s+= ": ${payload}"
    logDebug s
  }
  try {
    method(statusParams) { response -> 
      if (response.success) {
        result = response.data
      } else {
        maybeLogError "Failed request for ${path} ${queryString} with payload ${payload}:(${response.status}) ${response.data}"
        state.status = sAPIERR
      }
    }
    if(result && debugOk())logDebug "Response data from SleepNumber: ${result}"
    return result
  } catch (Exception e) {
    if (e.toString().contains("Unauthorized") && !alreadyTriedRequest) {
      // The session is invalid so retry login before giving up.
      info "Unauthorized, retrying login"
      if (useAwsO) {
        loginAws()
      } else {
        login()
      }
      return httpRequest(path, method, body, query, true)
    } else {
      // There was some other error so retry if that hasn't already been done
      // otherwise give up.  Not Found errors won't improve with retry to don't
      // bother.
      if (!alreadyTriedRequest && !e.toString().contains("Not Found")) {
        maybeLogError "Retrying failed request ${statusParams}\n${e}",e
        return httpRequest(path, method, body, query, true)
      } else {
        String err= "Error making request ${statusParams}\n${e}"
        if (e.toString().contains("Not Found")) {
          // Don't bother polluting logs for Not Found errors as they are likely
          // either intentional (trying to figure out if outlet exists) or a code
          // bug.  In the latter case we still want diagnostic data so we use
          // debug logging.
          debug err
          return result
        }
        maybeLogError err,e
        state.status = sAPIERR
        return result
      }
    }
  }
}

@Field static final String sAPIERR = 'API Error'

// Can't seem to use method reference to built-in so
// we create simple ones to pass around
def get(Map params, Closure closure) {
  httpGet(params, closure)
}

def put(Map params, Closure closure) {
  httpPut(params, closure)
}




/**
 * Only logs an error message if one wasn't logged within the last
 * N minutes where N is configurable.
 */
void maybeLogError(String msg, Exception e=null) {
  String ll= (String)settings.logLevel
  if (ll != sNL && ll.toInteger() == iZ) {
    return
  }
  Integer lim=(Integer)settings.limitErrorLogsMin
  if (!lim /* off */
      || (wnow() - lastErrorLogTime) > (lim * 60 * 1000)) {
    logError msg,e
    lastErrorLogTime = wnow()
  }
}

Boolean debugOk(){
  String ll= (String)settings.logLevel
  return  (ll != sNL && ll.toInteger() == i1) || (Boolean)settings.enableDebugLogging
}

void debug(String msg) { if ( debugOk()) { logDebug msg } }

Boolean infoOk() {
  String ll= (String)settings.logLevel
  return  (ll == sNL
          || (ll.toInteger() >= i1 && ll.toInteger() < i3)
          || (Boolean)settings.enableDebugLogging)
}

void info(String msg) { if ( infoOk()){ logInfo msg } }

Boolean warnOk(){
  String ll= (String)settings.logLevel
  return (ll == sNL || ll.toInteger() > iZ
          || (Boolean)settings.enableDebugLogging)
}

void warn(String msg) { if (warnOk()) { logWarn msg } }

@Field static final String sBLK         = ''
@Field static final String sSPACE         = ' '
@Field static final String sCLRRED        = 'red'
@Field static final String sCLRGRY        = 'gray'
@Field static final String sCLRORG        = 'orange'
@Field static final String sLINEBR        = '<br>'

private void logDebug(String msg) { log.debug logPrefix(msg, "purple") }
private void logInfo(String msg) { log.info sSPACE + logPrefix(msg, "#0299b1") }
private void logTrace(String msg) { log.trace logPrefix(msg, sCLRGRY) }
private void logWarn(String msg) { log.warn sSPACE + logPrefix(msg, sCLRORG) }

void logError(String msg, ex=null) {
  log.error logPrefix(msg, sCLRRED)
  String a; a=sNL
  try {
    if (ex) a = getExceptionMessageWithLine(ex)
  } catch (ignored) {}
  if(a) log.error logPrefix(a, sCLRRED)
}

@CompileStatic
static String logPrefix(String msg, String color = sNL) {
  return span("Sleep Number App (v" + appVersionFLD + ") | ", sCLRGRY) + span(msg, color)
}

@CompileStatic
static String span(String str, String clr=sNL, String sz=sNL, Boolean bld=false, Boolean br=false) { return str ? "<span ${(clr || sz || bld) ? "style='${clr ? "color: ${clr};" : sBLK}${sz ? "font-size: ${sz};" : sBLK}${bld ? "font-weight: bold;" : sBLK}'" : sBLK}>${str}</span>${br ? sLINEBR : sBLK}" : sBLK }





// wrappers

Long wnow() { return (Long)now() }
Object wparseJson(String a) { return parseJson(a) }
Boolean wtimeOfDayIsBetween(Date s, Date st, Date v) { return (Boolean)timeOfDayIsBetween(s,st,v) }
Date wtoDateTime(String t) { return (Date)toDateTime(t) }
void wrunIn(Long t, String meth, Map options=null){ runIn(t,meth,options) }
private void wrunInMillis(Long t,String m,Map d){ runInMillis(t,m,d) }
private void wpauseExecution(Long t){ pauseExecution(t) }
private gtSt(String nm){ return state."${nm}" }
/** assign to state  */
private void assignSt(String nm,v){ state."${nm}"=v }

String gtAid(){ return app.getId() }



// in memory timers

@Field volatile static Map<String,Map> tsDtMapFLD=[:]

@CompileStatic
private void updTsVal(String key, String dt=sNL) {
  String val = dt ?: getDtNow()
//  if(key in svdTSValsFLD) { updServerItem(key, val); return }

  String appId=gtAid()
  Map data=tsDtMapFLD[appId] ?: [:]
  if(key) data[key]=val
  tsDtMapFLD[appId]=data
  tsDtMapFLD=tsDtMapFLD
}

@CompileStatic
private void remTsVal(key) {
  String appId=gtAid()
  Map data=tsDtMapFLD[appId] ?: [:]
  if(key) {
    if(key instanceof List) {
      List<String> aa = (List<String>)key
      aa.each { String k->
        if(data.containsKey(k)) { data.remove(k) }
        //if(k in svdTSValsFLD) { remServerItem(k) }
      }
    } else {
      String sKey = (String)key
      if(data.containsKey(sKey)) { data.remove(sKey) }
      //if(sKey in svdTSValsFLD) { remServerItem(sKey) }
    }
    tsDtMapFLD[appId]=data
    tsDtMapFLD=tsDtMapFLD
  }
}

//@Field static final List<String> svdTSValsFLD = ["lastCookieRrshDt", "lastServerWakeDt"]

@CompileStatic
private String getTsVal(String key) {
/*  if(key in svdTSValsFLD) {
    return (String)getServerItem(key)
  }*/
  String appId=gtAid()
  Map tsMap=tsDtMapFLD[appId]
  if(key && tsMap && tsMap[key]) { return (String)tsMap[key] }
  return sNL
}

@CompileStatic
Integer getLastTsValSecs(String val, Integer nullVal=1000000) {
  String ts= val ? getTsVal(val) : sNL
  return ts ? GetTimeDiffSeconds(ts).toInteger() : nullVal
}

/*
@Field volatile static Map<String,Map> serverDataMapFLD=[:]

void updServerItem(String key, val) {
  Map data
  data = atomicState?.serverDataMap
  data =  data ?: [:]
  if(key) {
    String appId=gtAid()
    data[key] = val
    atomicState.serverDataMap = data
    serverDataMapFLD[appId]= [:]
    serverDataMapFLD = serverDataMapFLD
  }
}

void remServerItem(key) {
  Map data
  data = atomicState?.serverDataMap
  data =  data ?: [:]
  if(key) {
    if(key instanceof List) {
      List<String> aa = (List<String>)key
      aa?.each { String k-> if(data.containsKey(k)) { data.remove(k) } }
    } else { if(data.containsKey((String)key)) { data.remove((String)key) } }
    String appId=gtAid()
    atomicState?.serverDataMap = data
    serverDataMapFLD[appId]= [:]
    serverDataMapFLD = serverDataMapFLD
  }
}

def getServerItem(String key) {
  String appId=gtAid()
  Map fdata
  fdata = serverDataMapFLD[appId]
  if(fdata == null) fdata = [:]
  if(key) {
    if(fdata[key] == null) {
      Map sMap = atomicState?.serverDataMap
      if(sMap && sMap[key]) {
        fdata[key]=sMap[key]
      }
    }
    return fdata[key]
  }
  return null
} */

@CompileStatic
Long GetTimeDiffSeconds(String lastDate, String sender=sNL) {
  try {
    if(lastDate?.contains("dtNow")) { return 10000 }
    Date lastDt = Date.parse("E MMM dd HH:mm:ss z yyyy", lastDate)
    Long start = lastDt.getTime()
    Long stop = wnow()
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

private static TimeZone mTZ(){ return TimeZone.getDefault() } // (TimeZone)location.timeZone

@CompileStatic
static String formatDt(Date dt, Boolean tzChg=true) {
  SimpleDateFormat tf = new SimpleDateFormat("E MMM dd HH:mm:ss z yyyy")
  if(tzChg) { if(mTZ()) { tf.setTimeZone(mTZ()) } }
  return (String)tf.format(dt)
}


// vim: tabstop=2 shiftwidth=2 expandtab
