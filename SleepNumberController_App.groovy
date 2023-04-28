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
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.JsonBuilder
import groovy.json.JsonException
import groovy.transform.CompileStatic
import groovy.transform.Field

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import org.json.JSONObject

@Field static ConcurrentLinkedQueue requestQueue = new ConcurrentLinkedQueue()
@Field static Semaphore mutex = new Semaphore(1)
@Field volatile static Long lastLockTime = 0
@Field static Long lastErrorLogTime = 0


@Field final String API_HOST = "prod-api.sleepiq.sleepnumber.com"
@Field final String API_URL = "https://" + API_HOST
@Field final String LOGIN_HOST = "l06it26kuh.execute-api.us-east-1.amazonaws.com"
@Field final String LOGIN_URL = "https://" + LOGIN_HOST
@Field final String LOGIN_CLIENT_ID = "jpapgmsdvsh9rikn4ujkodala"
@Field final String USER_AGENT = "SleepIQ/1669639706 CFNetwork/1399 Darwin/22.1.0"
@Field final String SN_APP_VERSION = "4.4.1"

@Field static final ArrayList<String> VALID_ACTUATORS = ["H", "F"]
@Field static final ArrayList<Integer> VALID_WARMING_TIMES = [30, 60, 120, 180, 240, 300, 360]
@Field static final ArrayList<Integer> VALID_WARMING_TEMPS = [0, 31, 57, 72]
@Field static final ArrayList<Integer> VALID_PRESET_TIMES = [0, 15, 30, 45, 60, 120, 180]
@Field static final ArrayList<Integer> VALID_PRESETS = [1, 2, 3, 4, 5, 6]
@Field static final ArrayList<Integer> VALID_LIGHT_TIMES = [15, 30, 45, 60, 120, 180]
@Field static final ArrayList<Integer> VALID_LIGHT_BRIGHTNESS = [1, 30, 100]
@Field static final Map<String, String> LOG_LEVELS = ["0": "Off", "1": "Debug", "2": "Info", "3": "Warn"]

@Field static final String PAUSE = "Pause"
@Field static final String RESUME = "Resume"

definition(
  name: "Sleep Number Controller",
  namespace: "rvrolyk",
  author: "Russ Vrolyk",
  description: "Control your Sleep Number Flexfit bed.",
  category: "Integrations",
  iconUrl: "",
  iconX2Url: "",
  importUrl: "https://raw.github.com/rvrolyk/SleepNumberController/master/SleepNumberController_App.groovy"
)

preferences {
  page name: "homePage", install: true, uninstall: true
  page name: "findBedPage"
  page name: "selectBedPage"
  page name: "createBedPage"
  page name: "diagnosticsPage"
}

/**
 * Required handler for pause button.
 */
def appButtonHandler(btn) {
  if (btn == "pause") {
    state.paused = !(Boolean) state.paused
    if ((Boolean) state.paused) {
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

Map homePage() {
  List<Map> currentDevices = getBedDeviceData()

  dynamicPage(name: "homePage") {
    if ((Boolean)state.paused) {
      state.pauseButtonName = RESUME
    } else {
      state.pauseButtonName = PAUSE
    }
    section("") {
      input name: "pause", type: "button", title: state.pauseButtonName
    }
    section("<b>Settings</b>") {
      input name: "login", type: "text", title: "sleepnumber.com email",
          description: "Email address you use with Sleep Number", submitOnChange: true
      input name: "password", type: "password", title: "sleepnumber.com password",
          description: "Password you use with Sleep Number", submitOnChange: true
      // User may opt for constant refresh or a variable one.
      Boolean defaultVariableRefresh = settings.variableRefresh != null && !(Boolean) settings.variableRefresh ? false : (Integer) settings.refreshInterval == null
      input "variableRefresh", "bool", title: "Use variable refresh interval? (recommended)", defaultValue: defaultVariableRefresh,
         submitOnChange: true
      if (defaultVariableRefresh || (Boolean) settings.variableRefresh) {
        input name: "dayInterval", type: "number", title: "Daytime Refresh Interval (minutes; 0-59)",
            description: "How often to refresh bed state during the day", defaultValue: 30
        input name: "nightInterval", type: "number", title: "Nighttime Refresh Interval (minutes; 0-59)",
              description: "How often to refresh bed state during the night", defaultValue: 1
        input "variableRefreshModes", "bool", title: "Use modes to control variable refresh interval", defaultValue: false, submitOnChange: true
        if ((Boolean) settings.variableRefreshModes) {
          input name: "nightMode", type: "mode", title: "Modes for night (anything else will be day)", multiple: true, submitOnChange: true
        } else {
          input name: "dayStart", type: "time", title: "Day start time",
              description: "Time when day will start if both sides are out of bed for more than 5 minutes", submitOnChange: true
          input name: "nightStart", type: "time", title: "Night start time", description: "Time when night will start", submitOnChange: true
        }
      } else {
        input name: "refreshInterval", type: "number", title: "Refresh Interval (minutes; 0-59)",
            description: "How often to refresh bed state", defaultValue: 1
      }
    }

    section("<b>Bed Management</b>") {
      if (!(String) settings.login || !(String) settings.password) {
        paragraph "Add login and password to find beds"
      } else {
        if (currentDevices.size() > 0) {
          paragraph "Current beds"
          currentDevices.each { device ->
            String output = ""
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
        if ((String) settings.login && (String) settings.password) {
          href "findBedPage", title: "Create or Modify Bed", description: "Search for beds"
        }
      }
    }

    section(title: "") {
      href url: "https://github.com/rvrolyk/SleepNumberController", style: "external", required: false, title: "Documentation", description: "Tap to open browser"
    }
 
    section(title: "") {
      href url: "https://www.paypal.me/rvrolyk", style: "external", required: false, title: "Donations", description: "Tap to open browser for PayPal"
    }
       
    section(title: "<b>Advanced Settings</b>") {
      String defaultName = "Sleep Number Controller"
      if ((String)state.displayName) {
        defaultName = state.displayName
        app.updateLabel(defaultName)
      }
      label title: "Assign an app name", required: false, defaultValue: defaultName
      input name: "modes", type: "mode", title: "Only refresh for specific mode(s)", required: false, multiple: true, submitOnChange: true
      input name: "switchToDisable", type: "capability.switch", title: "Switch to disable refreshes", required: false, submitOnChange: true
      input "enableDebugLogging", "bool", title: "Enable debug logging for 30m?", defaultValue: false, required: true, submitOnChange: true
      input "logLevel", "enum", title: "Choose the logging level", defaultValue: "2", submitOnChange: true, options: LOG_LEVELS
      input "limitErrorLogsMin", "number", title: "How often to allow error logs (minutes), 0 for all the time. <br><font size=-1>(Only applies when log level is not off)</font> ", defaultValue: 0, submitOnChange: true 
      if ((String)settings.login && (String)settings.password) {
        href "diagnosticsPage", title: "Diagnostics", description: "Show diagnostic info"
      }
      input "useAwsOAuth", "bool", title: "(Beta) Use AWS OAuth", required: false, submitOnChange: true, defaultValue: false
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
  state.session = null // next run will refresh all tokens/cookies
  state.variableRefresh = ""
  initialize()
  if ((Boolean) settings.enableDebugLogging) {
    runIn(1800, logsOff)
  }
}

void logsOff() {
  if ((Boolean) settings.enableDebugLogging) {
    // Log this information regardless of user setting.
    logInfo "debug logging disabled..."
    app.updateSetting "enableDebugLogging", [value: "false", type: "bool"]
  }
}

def initialize() {
  Integer interval = (Integer)settings.refreshInterval
  if (interval <= 0 && !(Boolean)settings.variableRefresh) {
    logError "Invalid refresh interval ${interval}"
  }
  Integer day = (Integer) settings.dayInterval
  Integer night = (Integer) settings.nightInterval
  if ((Boolean) settings.variableRefresh && (day <= 0 || night <= 0)) {
    logError "Invalid refresh intervals ${day} or ${night}"
  }
  if ((Boolean)settings.variableRefreshModes) {
    subscribe(location, "mode", configureVariableRefreshInterval)
  }
  setRefreshInterval(new BigDecimal(0) /* force picking from settings */, "" /* ignored */)
  initializeBedInfo()
  refreshChildDevices()
  updateLabel()
}

void updateLabel() {
  // Store the user's original label in state.displayName
  String appLabel = (String) app.label
  if (!appLabel.contains("<span") && (String) state.displayName != appLabel) {
    state.displayName = appLabel
  }
  if ((String) state.status || (String) state.paused) {
    String status = (String) state.status
    StringBuilder label = new StringBuilder("${state.displayName} <span style=color:")
    if ((Boolean) state.paused) {
      status = "(Paused)"
      label.append("red")
    } else if (state.status == "Online") {
      label.append("green")
    } else if (state.status.contains("Login")) {
      label.append("red")
    } else {
      label.append("orange")
    }
    label.append(">${status}</span>")
    app.updateLabel(label.toString())
  }
}

/*------------------ Bed state helpers  ------------------*/
static String getBedDeviceId(ChildDeviceWrapper bed) {
  return (String)((Map) bed.getState()).bedId
}

static String getBedDeviceSide(ChildDeviceWrapper bed) {
  return (String)((Map) bed.getState()).side
}

static String getBedDeviceType(ChildDeviceWrapper bed) {
  return (String)((Map) bed.getState()).type
}

void initializeBedInfo() {
  debug "Setting up bed info"
  Map bedInfo = getBeds()
  Map<String, Map> stateBedInfo = [:]
  bedInfo.beds.each() { Map bed ->
    String id = bed.bedId.toString()
    debug("Bed id %s", id)
    if (!stateBedInfo.containsKey(id)) {
      stateBedInfo[id] = [:]
    }
    List<String> components = []
    for (Map component : (List<Map>) bed.components) {
      if ((String) component.type == "Base"
          && (String) component.model.toLowerCase().contains("integrated")) {
        // Integrated bases need to be treated separately as they don't appear to have
        // foundation status endpoints so don't lump this with a base type directly.
        components << "Integrated Base"
      } else {
        components << component.type
      }
    }
    stateBedInfo[bed.bedId].components = components
  }
  if (!stateBedInfo) {
    warn "No bed state set up"
  }
  state.bedInfo = stateBedInfo
}

/**
 * Gets all bed child devices even if they're in a virtual container.
 * Will not return the virtual container(s) or children of a parent
 * device.
 */
List<ChildDeviceWrapper> getBedDevices() {
  List<ChildDeviceWrapper> children = []
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
  List<ChildDeviceWrapper> devices = getBedDevices()
  List<Map> output = []
  devices.each { device ->
    String side = getBedDeviceSide(device)
    String bedId = getBedDeviceId(device)
    String type = getBedDeviceType(device) ?: "Parent"

    output << [
      name: (String) device.label,
      type: type,
      side: side,
      deviceId: device.id,
      bedId: bedId,
      isChild: false,
    ]
    device.getChildDevices().each { child ->
      output << [
        name: child.label,
        type: device.getChildType(child.deviceNetworkId),
        side: side,
        deviceId: child.id,
        bedId: bedId,
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
  Set<String> typeList = data.collect { if ((String) it.bedId == bedId) { return (String) it.type } }

  // cull NULL entries
  typeList = typeList.findAll()
  return typeList
}

// Use with #schedule as apparently it's not good to mix #runIn method call
// and #schedule method call.
void scheduledRefreshChildDevices() {
  refreshChildDevices()
  if ((Boolean) settings.variableRefresh) {
    // If we're using variable refresh then try to reconfigure it since bed states
    // have been updated and we may be in daytime.
    configureVariableRefreshInterval()
  }
}

void refreshChildDevices() {
  // Only refresh if mode is a selected one
  List setModes = (List) settings.modes
  if (setModes && !setModes.contains(location.mode)) {
    debug "Skipping refresh, not the right mode"
    return
  }
  // If there's a switch defined and it's on, don't bother refreshing at all
  def disableSwitch = settings.switchToDisable
  if (disableSwitch && (String) disableSwitch.currentValue("switch") == "on") {
    debug "Skipping refresh, switch to disable is on"
    return
  }
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
  debug ("setRefreshInterval(%s)", val)
  Random random = new Random()
  Integer randomInt = random.nextInt(40) + 4
  if (val && val > 0) {
    schedule("${randomInt} /${val} * * * ?", "scheduledRefreshChildDevices")
  } else {
    if (!(Boolean) settings.variableRefresh) {
      Integer interval = (Integer) settings.refreshInterval
      debug ("Resetting interval to %s", interval)
      schedule("${randomInt} /${interval} * * * ?", "scheduledRefreshChildDevices")
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
  Boolean night = false

  if ((Boolean) settings.variableRefreshModes) {
    if ((List) settings.nightMode.contains(location.mode)) {
      night = true
    } else {
      night = false
    }
  } else {
    // Gather presence state of all child devices
    List presentChildren = getBedDevices().findAll {
      (!it.getState().type || it.getState()?.type == "presence") && (Boolean) it.isPresent()
    }
    Date now = new Date()
    if (timeOfDayIsBetween(toDateTime(settings.dayStart), toDateTime((String) settings.nightStart), now)) {
      if (presentChildren.size() > 0) return // if someone is still in bed, don't change anything
      night = false
    } else {
      night = true
    }
  }

  Random random = new Random()
  Integer randomInt = random.nextInt(40) + 4

  if (night) {
    String varRefresh = (String) state.variableRefresh
    // Don't bother setting the schedule if we are already set to night.
    if (varRefresh != "night") {
      Integer interval = (Integer) settings.nightInterval
      info ("Setting interval to night. Refreshing every %s minutes.", interval)
      schedule("${randomInt} /${interval} * * * ?", "scheduledRefreshChildDevices")
      state.variableRefresh = "night"
    }
  } else if (varRefresh != "day") {
    Integer interval = (Integer) settings.dayInterval
    info ("Setting interval to day. Refreshing every %s minutes.", interval)
    schedule("${randomInt} /${interval} * * * ?", "scheduledRefreshChildDevices")
    state.variableRefresh = "day"
  }
}

Map findBedPage() {
  Map responseData = getBedData()
  List devices = getBedDevices()
  List childDevices = []
  dynamicPage(name: "findBedPage") {
    if (responseData && responseData.beds.size() > 0) {
      responseData.beds.each { bed ->
        List sidesSeen = []
        section("Bed: ${bed.bedId}") {
          paragraph "<br>Note: <i>Sides are labeled as if you are laying in bed.</i>"
          if (devices.size() > 0) {
            for (def dev : devices) {
              if (getBedDeviceId(dev) != bed.bedId) {
                debug "bedId's don't match, skipping"
                continue
              }
              if (!getBedDeviceType(dev) || getBedDeviceType(dev) == "presence") {
                if (!getBedDeviceType(dev)) {
                  childDevices << getBedDeviceSide(dev)
                }
                sidesSeen << getBedDeviceSide(dev)
                addBedSelectLink(getBedDeviceSide(dev), bed.bedId, dev.label, "modify")
              }
            }
            if (childDevices.size() < 2) {
              input "createNewChildDevices", "bool", title: "Create new child device types", defaultValue: false, submitOnChange: true
              if (settings.createNewChildDevices) {
                if (!childDevices.contains("Left")) {
                  addBedSelectLink("Left", bed.bedId)
                }
                if (!childDevices.contains("Right")) {
                  addBedSelectLink("Right", bed.bedId)
                }
              }
            }
          }
          if (!sidesSeen.contains("Left")) {
            addBedSelectLink("Left", bed.bedId)
          }
          if (!sidesSeen.contains("Right")) {
            addBedSelectLink("Right", bed.bedId)
          }
        }
      }
    } else {
      section {
        paragraph "No Beds Found"
      }
    }
  }
}

void addBedSelectLink(String side, String bedId, String label = "", String modifyCreate = "create") {
  app.updateSetting("newDeviceName", [value: "", type: "text"])
  href "selectBedPage", name: "Bed: ${bedId}", title: label ?: "${side} Side", description: "Click to ${modifyCreate}",
          params: [bedId: bedId, side: side, label: label]
}

static String presenceText(presence) {
  return presence ? "Present" : "Not Present"
}

Map selectBedPage(Map params) {
  initializeBedInfo()
  dynamicPage(name: "selectBedPage") {
    if (!params?.bedId) {
      section {
        href "homePage", title: "Home", description: null
      }
      return
    }
    section {
      paragraph """<b>Instructions</b>
Enter a name, then choose whether or not to use child devices or a virtual container for the devices and then choose the types of devices to create.
Note that if using child devices, the parent device will contain all the special commands along with bed specific status while the children are simple
switches or dimmers.  Otherwise, all devices are the same on Hubitat, the only difference is how they behave to dim and on/off commands.  This is so that they may be used with external assistants such as Google Assistant or Amazon Alexa.  If you don't care about such use cases (and only want RM control or just presence), you can just use the presence type.
<br>
See <a href="https://community.hubitat.com/t/release-virtual-container-driver/4440" target=_blank>this post</a> for virtual container.
"""
        paragraph """<b>Device information</b>
Bed ID: ${params.bedId}
Side: ${params.side}
""" 
    }
    section {
      String newName = (String) settings.newDeviceName
      String label = (String) params.label
      String name = newName?.trim() ? newName : label?.trim() ? label : ""
      if (!settings.newDeviceName) {
        app.updateSetting("newDeviceName", [value: name, type: "text"])
      }
      input "newDeviceName", "text", title: "Device Name", defaultValue: name,
          description: "What prefix do you want for the devices?", submitOnChange: true,
          required: true
      input "useChildDevices", "bool", title: "Use child devices? (recommended)", defaultValue: true,
         submitOnChange: true
      if (!(Boolean) settings.useChildDevices) {
        input "useContainer", "bool", title: "Use virtual container?", defaultValue: false,
           submitOnChange: true
      }
      String side = ((String) params.side).toLowerCase()
      paragraph "A presence type device exposes on/off as switching to a preset level (on) and flat (off).  Dimming will change the Sleep Number."
      if ((Boolean) settings.useChildDevices) {
        paragraph "This is the parent device when child devices are used"
        settings.createPresence = true
      } else {
        input "createPresence", "bool",
            title: "Create presence device for ${side} side?",
            defaultValue: true, submitOnChange: true
      }
      paragraph "A head type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the head position (0 is flat, 100 is fully raised)."
      input "createHeadControl", "bool",
         title: "Create device to control the head of the ${side} side?",
         defaultValue: true, submitOnChange: true
      paragraph "A foot type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the foot position (0 is flat, 100 is fully raised)."
      input "createFootControl", "bool",
         title: "Create device to control the foot of the ${side} side?",
         defaultValue: true, submitOnChange: true
      if (((List<String>) ((Map) state.bedInfo[params.bedId]).components).contains("Warming")) {
        paragraph "A foot type device exposes on/off as switching the foot warming on or off.  Dimming will change the heat levels (1: low, 2: medium, 3: high)."
        input "createFootWarmer", "bool",
           title: "Create device to control the foot warmer of the ${side} side?",
           defaultValue: true, submitOnChange: true
      }
      if ((Boolean) settings.useChildDevices) {
        determineUnderbedLightSetup(params.bedId)
        paragraph "Underbed lighting creates a dimmer allowing the light to be turned on or off at different levels with timer based on parent device preference."
        input "createUnderbedLighting", "bool",
         title: "Create device to control the underbed lighting of the ${side} side?",
           defaultValue: false, submitOnChange: true
        if (((List) ((Map) state.bedInfo[params.bedId]).outlets).size > 1) {
          paragraph "Outlet creates a switch allowing foundation outlet for this side to be turned on or off."
          input "createOutlet", "bool",
           title: "Create device to control the outlet of the ${side} side?",
             defaultValue: false, submitOnChange: true
        }
      }
    }
    section {
      StringBuilder msg = new StringBuilder("Will create the following devices")
      String containerName = ""
      List<String> types = []
      if ((Boolean) settings.useChildDevices) {
        settings.useContainer = false
        msg.append(" with each side as a primary device and each type as a child device of the side")
      } else if ((Boolean) settings.useContainer) {
        containerName = "${newDeviceName} Container"
        msg.append(" in virtual container '").append(containerName).append("'")
      }
      msg.append(":<ol>")
      if ((Boolean) settings.createPresence) {
        msg.append("<li>").append(createDeviceLabel(newDeviceName, 'presence')).append("</li>")
        types.add("presence")
      }
      if ((Boolean) settings.createHeadControl) {
        msg.append("<li>").append(createDeviceLabel(newDeviceName, 'head')).append("</li>")
        types.add("head")
      }
      if ((Boolean) settings.createFootControl) {
        msg.append("<li>").append(createDeviceLabel(newDeviceName, 'foot')).append("</li>")
        types.add("foot")
      }
      if ((Boolean) settings.createFootWarmer) {
        msg.append("<li>").append(createDeviceLabel(newDeviceName, 'foot warmer')).append("</li>")
        types.add("foot warmer")
      }
      if ((Boolean) settings.createUnderbedLighting && (Boolean) settings.useChildDevices) {
        msg.append("<li>").append(createDeviceLabel(newDeviceName, 'underbed light')).append("</li>")
        types.add("underbed light")
      }
      if ((Boolean) settings.createOutlet && (Boolean) settings.useChildDevices) {
        msg.append("<li>").append(createDeviceLabel(newDeviceName, 'outlet')).append("</li>")
        types.add("outlet")
      }
      msg.append("</ol>")
      paragraph msg.toString()
      paragraph "<b>Click create below to continue</b>"
      href "createBedPage", title: "Create Devices", description: null,
      params: [
        presence: params.present,
        bedId: params.bedId,
        side: params.side,
        useChildDevices: settings.useChildDevices,
        useContainer: settings.useContainer,
        containerName: containerName,
        types: types
      ]
    }
  }
}

static String createDeviceLabel(String name, String type) {
  switch (type) {
    case "presence":
      return "${name}"
    case "head":
      return "${name} Head"
    case "foot":
      return "${name} Foot"
    case "foot warmer":
      return "${name} Foot Warmer"
    case "underbed light":
      return "${name} Underbed Light"
    case "outlet":
      return "${name} Outlet"
    default:
      return "${name} Unknown"
  }
}

Map createBedPage(Map params) {
  ChildDeviceWrapper container = null
  if ((Boolean) params.useContainer) {
    container = createContainer((String) params.bedId, (String) params.containerName, (String) params.side)
  }
  List<ChildDeviceWrapper> existingDevices = getBedDevices()
  List<ChildDeviceWrapper> devices = []
  // TODO: Consider allowing more than one identical device for debug purposes.
  if ((Boolean) params.useChildDevices) {
    // Bed Ids seem to always be negative so convert to positive for the device
    // id for better formatting.
    Long bedId = Math.abs(Long.valueOf((String) params.bedId))
    String deviceId = "sleepnumber.${bedId}.${params.side}"
    String label = createDeviceLabel((String) settings.newDeviceName, "presence")
    ChildDeviceWrapper parent = existingDevices.find{ (String) it.deviceNetworkId == deviceId }
    if (parent) {
      info("Parent device %s already exists", deviceId)
    } else {
      debug("Creating parent device %s", deviceId)
      parent = addChildDevice(NAMESPACE, DRIVER_NAME, deviceId, null, [label: label])
      parent.setStatus(params.presence)
      parent.setBedId(params.bedId)
      parent.setSide(params.side)
      devices.add(parent)
    }
    // If we are using child devices then we create a presence device and
    // all others are children of it.
    ((List<String>) params.types).each { String type ->
      if (type != "presence") {
        String childId = deviceId + "-" + type.replaceAll(" ", "")
        String driverType = null
        switch (type) {
          case "outlet":
            driverType = "Switch"
            break
          case "head":
          case "foot":
          case "foot warmer":
          case "underbed light":
            driverType = "Dimmer"
        }
        ChildDeviceWrapper newDevice = parent.createChildDevice(childId, "Generic Component ${driverType}",
                createDeviceLabel((String) settings.newDeviceName, type))
        if (newDevice) {
          devices.add(newDevice)
        }
      }
    }
  } else {
    ((List<String>) params.types).each { String type ->
      String deviceId = "sleepnumber.${params.bedId}.${params.side}.${type.replaceAll(' ', '_')}"
      if (existingDevices.find{ it.data.vcId == deviceId }) {
        info("Not creating device %s, it already exists", deviceId)
      } else {
        String label = createDeviceLabel(settings.newDeviceName, type)
        ChildDeviceWrapper device
        if (container) {
          debug("Creating new child device %s with label %s in container %s", deviceId, label, params.containerName)
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
  settings.newDeviceName = null
  dynamicPage(name: "selectDevicePage") {
    section {
      StringBuilder header = new StringBuilder("Created new devices")
      if (params.useChildDevices) {
        header.append(" using child devices")
      } else if (params.useContainer) {
        header.append(" in container").append(params.containerName)
      }
      header.append(":")
      paragraph(header.toString())
      StringBuilder displayInfo = new StringBuilder("<ol>")
      devices.each { device ->
        displayInfo.append("<li>")
        displayInfo.append(device.label)
        if (!params.useChildDevices) {
          displayInfo.append("<br>Bed ID: ").append(getBedDeviceId(device))
          displayInfo.append("<br>Side: ").append(getBedDeviceSide(device))
          displayInfo.append("<br>Type: ").append(getBedDeviceType(device))
        }
        displayInfo.append("</li>")
      }
      displayInfo.append("</ol>")
      paragraph displayInfo.toString()
    }
    section {
      href "findBedPage", title: "Back to Bed List", description: null
    }
  }
}

Map diagnosticsPage(params) {
  Map bedInfo = getBeds()
  dynamicPage(name: "diagnosticsPage") {
    bedInfo.beds.each { Map bed ->
      section("Bed: ${bed.bedId}") {
        StringBuilder bedOutput = new StringBuilder("<ul>")
        bedOutput.append("<li>Size: ").append(bed.size)
        bedOutput.append("<li>Dual Sleep: ").append(bed.dualSleep)
        bedOutput.append("<li>Components:")
        for (def component : bed.components) {
          bedOutput.append("<ul>")
          bedOutput.append("<li>Type: ").append(component.type)
          bedOutput.append("<li>Status: ").append(component.status)
          bedOutput.append("<li>Model: ").append(component.model)
          bedOutput.append("</ul>")
        }
        paragraph bedOutput.toString()
      }
    }
    section("Send Requests") {
        input "requestType", "enum", title: "Request type", options: ["PUT", "GET"]
        input "requestPath", "text", title: "Request path", description: "Full path including bed id if needed"
        input "requestBody", "text", title: "Request Body in JSON"
        input "requestQuery", "text", title: "Extra query key/value pairs in JSON"
        href "diagnosticsPage", title: "Send request", description: null, params: [
          requestType: (String) settings.requestType,
          requestPath: (String) settings.requestPath,
          requestBody: (String) settings.requestBody,
          requestQuery: (String) settings.requestQuery
        ]
        if (params && params.requestPath && params.requestType) {
          Map body
          if (params.requestBody) {
            try {
              body = (Map) parseJson(params.requestBody)
            } catch (JsonException e) {
              maybeLogError("%s : %s", params.requestBody, e)
            }
          }
          Map query = [:]
          if (params.requestQuery) {
            try {
              query = (Map) parseJson(params.requestQuery)
            } catch (JsonException e) {
              maybeLogError("%s : %s", params.requestQuery, e)
            }
          }
          Map response = httpRequest((String)params.requestPath,
                                     (String) settings.requestType == "PUT" ? this.&put : this.&get,
                                     body,
                                     query,
                                     true)
          paragraph "${response}"
        }
    }
    section("Authentication") {
      href "diagnosticsPage", title: "Clear session info", description: null, params: [clearSession: true]
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
  ChildDeviceWrapper container = getChildDevices().find{(String) it.typeName == "Virtual Container" && (String) it.label == containerName}
  if(!container) {
    debug("Creating container %s", containerName)
    try {
      container = addChildDevice("stephack", "Virtual Container", "${app.id}.${bedId}.${side}", null,
          [name: containerName, label: containerName, completedSetup: true]) 
    } catch (e) {
      logError("Container device creation failed with error = ", e)
      return null
    }
  }
  return container
}

@CompileStatic
Map getBedData() {
  Map responseData = getFamilyStatus()
  processBedData(responseData)
  return responseData
}

/**
 * Updates the bed devices with the given data.
 */
void processBedData(Map responseData) {
  if (!responseData || responseData.size() == 0) {
    debug "Empty response data"
    return
  }
  debug("Response data from SleepNumber: %s", responseData)
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

  for (ChildDeviceWrapper device : getBedDevices()) {
    String bedId = getBedDeviceId(device)
    String bedSideStr = getBedDeviceSide(device)
    if (!outletData.get(bedId)) {
      outletData[bedId] = [:]
      underbedLightData[bedId] = [:]
    }

    Set<String> deviceTypes = getBedDeviceTypes(bedId)
    for (Map bed : (List<Map>) responseData.beds) {
      // Make sure the various bed state info is set up so we can use it later.
      if (!state?.bedInfo || !state?.bedInfo[bed.bedId] || !state?.bedInfo[bed.bedId]?.components) {
        warn "state.bedInfo somehow lost, re-caching it"
        initializeBedInfo()
      }
      if (bedId == bed.bedId) {
        if (!bedFailures.get(bedId) && !privacyStatus.get(bedId)) {
          privacyStatus[bedId] = getPrivacyMode(bedId)
          if (!privacyStatus.get(bed.bedId)) {
            bedFailures[bedId] = true
          } 
        }
        // Note that it is possible to have a mattress without the base.  Prior, this used the presence of "Base"
        // in the bed status but it turns out SleepNumber doesn't always include that even when the base is
        // adjustable.  So instead, this relies on the devices the user created.
        if (!bedFailures.get(bedId)
            && !foundationStatus.get(bedId)
            && (deviceTypes.contains("head") || deviceTypes.contains("foot"))) {
          foundationStatus[bedId] = getFoundationStatus(bedId, bedSideStr)
          if (!foundationStatus.get(bedId)) {
            bedFailures[bedId] = true
          }
        }
        // So far, the presence of "Warming" in the bed status indicates a foot warmer.
        if (!bedFailures.get(bedId)
            && !footwarmingStatus.get(bedId)
            && state.bedInfo[bedId].components.contains("Warming")
            && (deviceTypes.contains("foot warmer") || deviceTypes.contains("footwarmer"))) {
          // Only try to update the warming state if the bed actually has it
          // and there's a device for it.
          footwarmingStatus[bedId] = getFootWarmingStatus(bedId)
          if (!footwarmingStatus.get(bedId)) {
            bedFailures[bedId] = true
          } 
        }
        // If there's underbed lighting or outlets then poll for that data as well.  Don't poll
        // otherwise since it's just another network request and may be unwanted.
        if (!bedFailures.get(bedId) && deviceTypes.contains("underbedlight")) {
          determineUnderbedLightSetup(bedId)
          if (!outletData[bedId][3]) {
            outletData[bedId][3] = getOutletState(bedId, 3)
            if (!outletData[bedId][3]) {
              bedFailures[bedId] = true
            }
          }
          if (!bedFailures.get(bedId) && !underbedLightData[bedId]) {
            underbedLightData[bedId] = getUnderbedLightState(bedId)
            if (!underbedLightData.get(bedId)) {
              bedFailures[bedId] = true
            } else {
              def brightnessData = getUnderbedLightBrightness(bedId)
              if (!brightnessData) {
                bedFailures[bedId] = true
              } else {
                underbedLightData[bedId] << brightnessData
              }
            }
          }
          if (state.bedInfo[bedId].outlets.size() > 1) {
            if (!bedFailures.get(bedId) && !outletData[bedId][4]) {
              outletData[bedId][4] = getOutletState(bedId, 4)
              if (!outletData[bedId][4]) {
                bedFailures[bedId] = true
              }
            }
          } else {
            outletData[bed.bedId][4] = outletData[bed.bedId][3]
          }
        }
        if (!bedFailures.get(bedId) && deviceTypes.contains("outlet")) {
          if (!outletData[bedId][1]) {
            outletData[bedId][1] = getOutletState(bedId, 1)
            if (!outletData[bedId][1]) {
              bedFailures[bedId] = true
            } else {
              outletData[bedId][2] = getOutletState(bedId, 2)
              if (!outletData[bedId][2]) {
                bedFailures[bedId] = true
              }
            }
          }
        }

        Map<String, Object> bedSide = bedSideStr == "Right" ? (Map<String, Object>) bed.rightSide : (Map<String, Object>) bed.leftSide
        device.setPresence(bedSide.isInBed)
        Map<String, String> statusMap = [
          sleepNumber: bedSide.sleepNumber,
          privacyMode: privacyStatus[bedId],
        ]
        if (underbedLightData.get(bedId)) {
          Integer outletNumber = bedSideStr == "Left" ? 3 : 4
          String bstate = underbedLightData[bedId]?.enableAuto ? "Auto" :
              outletData[bedId][outletNumber]?.setting == 1 ? "On" : "Off"
          String timer = bstate == "Auto" ? "Not set" :
              outletData[bedId][outletNumber]?.timer ? outletData[bedId][outletNumber]?.timer : "Forever"
          String brightness = underbedLightData[bedId]?."fs${bedSideStr}UnderbedLightPWM"
          statusMap << [
            underbedLightState: bstate,
            underbedLightTimer: timer,
            underbedLightBrightness: brightness,
          ]
        }
        if (outletData.get(bedId) && outletData[bedId][1]) {
          Integer outletNumber = bedSideStr == "Left" ? 1 : 2
          statusMap << [
            outletState: outletData[bedId][outletNumber]?.setting == 1 ? "On" : "Off"
          ]
        }
        // Check for valid foundation status and footwarming status data before trying to use it
        // as it's possible the HTTP calls failed.
        if (foundationStatus.get(bedId)) {
	        // Positions are in hex so convert to a decimal
          Integer headPosition = convertHexToNumber(foundationStatus.get(bedId)."fs${bedSideStr}HeadPosition")
          Integer footPosition = convertHexToNumber(foundationStatus.get(bed.bedId)."fs${bedSideStr}FootPosition")
          String bedPreset = foundationStatus.get(bedId)."fsCurrentPositionPreset${bedSideStr}"
          // There's also a MSB timer but not sure when that gets set.  Least significant bit seems used for all valid times.
          Integer positionTimer = convertHexToNumber(foundationStatus.get(bedId)."fs${bedSideStr}PositionTimerLSB")
          statusMap << [
            headPosition: headPosition,
            footPosition:  footPosition,
            positionPreset: bedPreset,
            positionPresetTimer: foundationStatus.get(bedId)."fsTimerPositionPreset${bedSideStr}",
            positionTimer: positionTimer
          ]
        } else if (!loggedError.get(bedId)) {
          debug("Not updating foundation state, %s", (bedFailures.get(bedId) ? "error making requests" : "no data"))
        }
        if (footwarmingStatus.get(bedId)) {
          statusMap << [
            footWarmingTemp: footwarmingStatus.get(bedId)."footWarmingStatus${bedSideStr}",
            footWarmingTimer: footwarmingStatus.get(bedId)."footWarmingTimer${bedSideStr}",
          ]
        } else if (!loggedError.get(bedId)) {
          debug("Not updating footwarming state, ", (bedFailures.get(bedId) ? "error making requests" : "no data"))
        }
        if (!sleepNumberFavorites.get(bedId)) {
          sleepNumberFavorites[bedId] = getSleepNumberFavorite(bedId)
        }
        Integer favorite = sleepNumberFavorites.get(bedId).get("sleepNumberFavorite" + bedSideStr, -1)
        if (favorite >= 0) {
          statusMap << [
            sleepNumberFavorite: favorite
          ]
        }
        // If the device has responsive air, fetch that status and add to the map
        if (!bedFailures.get(bedId) && device.getSetting("enableResponsiveAir")) {
          if (!responsiveAir.get(bedId)) {
            responsiveAir[bedId] = getResponsiveAirStatus(bedId)
          }
          String side = bedSideStr.toLowerCase()
          statusMap << [
            responsiveAir: responsiveAir.get(bedId)?."${side}SideEnabled" ?: ""
          ]
        }
        if (bedFailures.get(bedId)) {
          // Only log update errors once per bed
          loggedError[bedId] = true
        }
        device.setStatus(statusMap)
        break
      }
    }
  }
  if (bedFailures.size() == 0) {
    state.status = "Online"
  }
  debug("Cached data: %s\n%s", foundationStatus, footwarmingStatus)
}

@CompileStatic
Integer convertHexToNumber(String value) {
  if (value == "" || value == null) return 0
  try {
    return Integer.parseInt(value, 16)
  } catch (Exception e) {
    error("Failed to convert non-numeric value %s", e)
    return 0
  }
}

ChildDeviceWrapper findBedDevice(String deviceId) {
  ChildDeviceWrapper device = getBedDevices().find { deviceId == it.deviceNetworkId }
  if (!device) {
    error("Bed device with id %s is not a valid child", devId)
    return null
  }
  return device
}

@CompileStatic
Map getBeds() {
  debug "Getting information for all beds"
  return httpRequest("/rest/bed")
}

@CompileStatic
Map getFamilyStatus() {
  debug "Getting family status"
  return httpRequest("/rest/bed/familyStatus")
}

@CompileStatic
Map getFoundationStatus(String bedId, String currentSide) {
  debug("Getting Foundation Status for %s / %s", bedId, currentSide)
  return httpRequest("/rest/bed/${bedId}/foundation/status")
}

@CompileStatic
Map getFootWarmingStatus(String bedId) {
  debug("Getting Foot Warming Status for %s", bedId)
  return httpRequest("/rest/bed/${bedId}/foundation/footwarming")
}

@CompileStatic
Map getResponsiveAirStatus(String bedId) {
  debug("Getting responsive air status for %s", bedId)
  return httpRequest("/rest/bed/${bedId}/responsiveAir")
}

void setResponsiveAirState(Boolean state, String devId) {
  def device = findBedDevice(devId)
  if (!device) {
    return
  }
  Map body = [:] 
  String side = getBedDeviceSide(device)
  debug("Setting responsive air state %s to %s", side, state)
  if (side.toLowerCase().equals("right")) {
    body << [
      rightSideEnabled: state
    ]
  } else {
    body << [
      leftSideEnabled: state
    ]
  }
  httpRequestQueue(5, path: "/rest/bed/${getBedDeviceId(device)}/responsiveAir",
      body: body, runAfter: "refreshChildDevices")
}

/**
 * Params must be a Map containing keys actuator and position.
 * The side is derived from the specified device.
 */
void setFoundationAdjustment(Map params, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  if (!params?.actuator || params?.position == null) {
    error("Missing param values, actuator and position are required")
    return
  }
  if (!VALID_ACTUATORS.contains(params.actuator)) {
    error("Invalid actuator %s, valid values are %s", params.actuator, VALID_ACTUATORS)
    return
  }
  Map body = [
    speed: 0,
    actuator: params.actuator,
    side: getBedDeviceSide(device)[0],
    position: params.position
  ]
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // The timing appears to be linear which means it's 0.35 seconds per level adjusted for the head and 0.18
  // for the foot.
  Integer currentPosition = params.actuator == "H" ? device.currentValue("headPosition") : device.currentValue("footPosition")
  Integer positionDelta = Math.abs(params.position - currentPosition)
  Float movementDuration = params.actuator == "H" ? 0.35 : 0.18
  Integer waitTime = Math.round(movementDuration * positionDelta) + 1
  httpRequestQueue(waitTime, path: "/rest/bed/${getBedDeviceId(device)}/foundation/adjustment/micro",
      body: body, runAfter: "refreshChildDevices")
}

/**
 * Params must be a Map containing keys temp and timer.
 * The side is derived from the specified device.
 */
void setFootWarmingState(Map params, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  if (params?.temp == null || params?.timer == null) {
    error("Missing param values, temp and timer are required")
    return
  }
  if (!VALID_WARMING_TIMES.contains(params.timer)) {
    error("Invalid warming time %s, valid values are %s", params.timer, VALID_WARMING_TIMES)
    return
  }
  if (!VALID_WARMING_TEMPS.contains(params.temp)) {
    error("Invalid warming temp %s, valid values are %s", params.temp, VALID_WARMING_TEMPS)
    return
  }
  Map body = [
    "footWarmingTemp${getBedDeviceSide(device)}": params.temp,
    "footWarmingTimer${getBedDeviceSide(device)}": params.timer
  ]
  // Shouldn't take too long for the bed to reflect the new state, wait 5s just to be safe
  httpRequestQueue(5, path: "/rest/bed/${getBedDeviceId(device)}/foundation/footwarming",
      body: body, runAfter: "refreshChildDevices")
}

/**
 * Params must be a map containing keys preset and timer.
 * The side is derived from the specified device.
 */
void setFoundationTimer(Map params, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    error("Bed device with id %s is not a valid child", devId)
    return
  }
  if (params?.preset == null || params?.timer == null) {
    error("Missing param values, preset and timer are required")
    return
  }
  if (!VALID_PRESETS.contains(params.preset)) {
    error("Invalid preset %s, valid values are %s", params.preset, VALID_PRESETS)
    return
  }
  if (!VALID_PRESET_TIMES.contains(params.timer)) {
    error("Invalid timer %s, valid values are %s", params.timer, VALID_PRESET_TIMES)
    return
  }
  Map body = [
    side: getBedDeviceSide(device)[0],
    positionPreset: params.preset,
    positionTimer: params.timer
  ]
  httpRequest("/rest/bed/${getBedDeviceId(device)}/foundation/adjustment", this.&put, body)
  // Shouldn't take too long for the bed to reflect the new state, wait 5s just to be safe
  runIn(5, "refreshChildDevices")
}

/**
 * The side is derived from the specified device.
 */
void setFoundationPreset(Integer preset, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  if (!VALID_PRESETS.contains(preset)) {
    error("Invalid preset %s, valid values are %s", preset, VALID_PRESETS)
    return
  }
  Map body = [
    speed: 0,
    preset : preset,
    side: getBedDeviceSide(device).side[0],
  ]
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // Rather than attempt to derive the preset relative to the current state so we can compute
  // the time (as we do for adjustment), we just use the maximum.
  httpRequestQueue(35, path: "/rest/bed/${getBedDeviceId(device)}/foundation/preset",
      body: body, runAfter: "refreshChildDevices")
}

void stopFoundationMovement(Map ignored, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  Map body = [
    massageMotion: 0,
    headMotion: 1,
    footMotion: 1,
    side: getBedDeviceSide(device)[0]
  ]
  httpRequest("/rest/bed/${getBedDeviceId(device)}/foundation/motion", this.&put, body)
  runIn(5, "refreshChildDevices")
}

/**
 * The side is derived from the specified device.
 */
void setSleepNumber(BigDecimal number, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }

  Map body = [
    bedId: getBedDeviceId(device),
    sleepNumber: number,
    side: getBedDeviceSide(device)[0]
  ]
  // Not sure how long it takes to inflate or deflate so just wait 20s
  httpRequestQueue(20, path: "/rest/bed/${getBedDeviceId(device)}/sleepNumber",
      body: body, runAfter: "refreshChildDevices") 
}

@CompileStatic
String getPrivacyMode(String bedId) {
  debug("Getting Privacy Mode for %s", bedId)
  return httpRequest("/rest/bed/${bedId}/pauseMode", this.&get)?.pauseMode
}

void setPrivacyMode(Boolean mode, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  String pauseMode = mode ? "on" : "off"
  // Cloud request so no need to queue.
  httpRequest("/rest/bed/${getBedDeviceId(device)}/pauseMode", this.&put, null, [mode: pauseMode])
  runIn(2, "refreshChildDevices")
}

@CompileStatic
Map getSleepNumberFavorite(String bedId) {
  debug "Getting Sleep Number Favorites"
  return httpRequest("/rest/bed/${bedId}/sleepNumberFavorite", this.&get)
}

void setSleepNumberFavorite(String ignored, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  // Get the favorite for the device first, the most recent poll should be accurate
  // enough.
  Integer favorite = device.currentValue("sleepNumberFavorite")
  debug("sleep number favorite for %s is %s", getBedDeviceSide(device), favorite)
  if (!favorite || favorite < 0) {
    error("Unable to determine sleep number favorite for side %s", getBedDeviceSide(device))
    return
  }
  if (device.currentValue("sleepNumber") == favorite) {
    debug "Already at favorite"
    return
  }
  setSleepNumber(favorite, devId)
}

@CompileStatic
Map getOutletState(String bedId, Integer outlet) {
  return httpRequest("/rest/bed/${bedId}/foundation/outlet",
        this.&get, null, [outletId: outlet])
}

void setOutletState(String outletState, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  if (!outletState) {
    error "Missing outletState"
    return
  }
  Integer outletNum = device.getState().side == "Left" ? 1 : 2
  setOutletState(getBedDeviceId(device), outletNum, outletState)
}

/**
 * Sets the state of the given outlet.
 * @param bedId: the bed id
 * @param outletId: 1-4
 * @param state: on or off
 * @param timer: a valid minute duration (for outlets 3 and 4 only)
 * Timer is the only optional parameter.
 */
@CompileStatic
void setOutletState(String bedId, Integer outletId, String outletState, Integer timer = null) {
  if (!bedId || !outletId || !outletState) {
    error "Not all required arguments present"
    return
  }

  if (timer && !VALID_LIGHT_TIMES.contains(timer)) {
    error("Invalid underbed light timer %s.  Valid values are %s", timer, VALID_LIGHT_TIMES)
    return
  }

  outletState = (outletState ?: "").toLowerCase()

  if (outletId < 3) {
    // No timer is valid for outlets other than 3 and 4
    timer = null
  } else {
    timer = timer ?: 0
  }
  Map body = [
    timer: timer,
    setting: outletState == "on" ? 1 : 0,
    outletId: outletId
  ]
  httpRequestQueue(5, path: "/rest/bed/${bedId}/foundation/outlet",
      body: body, runAfter: "refreshChildDevices") 
}

@CompileStatic
Map getUnderbedLightState(String bedId) {
  httpRequest("/rest/bed/${bedId}/foundation/underbedLight", this.&get)
}

Map getUnderbedLightBrightness(String bedId) {
  determineUnderbedLightSetup(bedId)
  Map brightness = httpRequest("/rest/bed/${bedId}/foundation/system", this.&get)
  if (state.bedInfo[bedId].outlets.size() <= 1) {
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
  if (!state.bedInfo[bedId].outlets) {
    debug("Determining underbed lighting outlets for %s", bedId)
    // Determine if this bed has 1 or 2 underbed lighting outlets and store for future use.
    Map outlet3 = getOutletState(bedId, 3)
    Map outlet4 = getOutletState(bedId, 4)
    List outlets = []
    if (outlet3) {
      outlets << 3
    }
    if (outlet4) {
      outlets << 4
    }
    state.bedInfo[bedId].outlets = outlets
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
 * brighness: low, medium, high
 */
void setUnderbedLightState(Map params, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }

  if (!params.state) {
    error "Missing param state"
    return
  }

  params.state = params.state.toLowerCase()

  // A timer with a state of auto makes no sense, choose to honor state vs. timer.
  if (params.state == "auto") {
    params.timer = 0
  }
  if (params.timer) {
    params.state = "on"
  }

  if (params.brightness && !VALID_LIGHT_BRIGHTNESS.contains(params.brightness)) {
    error("Invalid underbed light brightness %s. Valid values are %s", params.brightness, VALID_LIGHT_BRIGHTNESS)
    return
  }

  // First set the light state.
  Map body = [
    enableAuto: params.state == "auto"
  ]
  httpRequest("/rest/bed/${getBedDeviceId(device)}/foundation/underbedLight", this.&put, body)

  determineUnderbedLightSetup(getBedDeviceId(device))
  def rightBrightness = params.brightness
  def leftBrightness = params.brightness
  Integer outletNum = 3
  if (state.bedInfo[getBedDeviceId(device)].outlets.size() > 1) {
    // Two outlets so set the side corresponding to the device rather than
    // defaulting to 3 (which should be a single light)
    if (getBedDeviceSide(device) == "Left") {
      outletNum = 3
      rightBrightness = null
      leftBrightness = params.brightness
    } else {
      outletNum = 4
      rightBrightness = params.brightness
      leftBrightness = null
    }
  }
  setOutletState(getBedDeviceId(device), outletNum,
      params.state == "auto" ? "off" : params.state, params.timer)

  // If brightness was given then set it.
  if (params.brightness) {
    body = [
      rightUnderbedLightPWM: rightBrightness,
      leftUnderbedLightPWM: leftBrightness
    ]
    httpRequest("/rest/bed/${getBedDeviceId(device)}/foundation/system", this.&put, body)
  }
  runIn(10, "refreshChildDevices") 
}

Map getSleepData(Map ignored, String devId) {
  ChildDeviceWrapper device = findBedDevice(devId)
  if (!device) {
    return
  }
  String bedId = getBedDeviceId(device)
  Map ids = [:]
  // We need a sleeper id for the side in order to look up sleep data.
  // Get sleeper to get list of sleeper ids
  debug("Getting sleeper ids for %s", bedId)
  Map sleepers = httpRequest("/rest/sleeper", this.&get)
  sleepers.sleepers.each() { sleeper ->
    if (sleeper.bedId == bedId) {
      String side = null
      switch (sleeper.side) {
        case 0:
          side = "Left"
          break
        case 1:
          side = "Right"
          break
        default:
          warn("Unknown sleeper info: %s", sleeper)
      }
      if (side != null) {
        ids[side] = sleeper.sleeperId
      }
    }
  }

  debug("Getting sleep data for %s", ids[getBedDeviceSide(device)])
  // Interval can be W1 for a week, D1 for a day and M1 for a month.
  return httpRequest("/rest/sleepData", this.&get, null, [
      interval: "D1",
      sleeper: ids[getBedDeviceSide(device)],
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
      jsonBody.put("RefreshToken", state.session.refreshToken)
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
          debug("refresh Success: (%s) %s", response.status, response.data)
          state.session.accessToken = response.data.data.AccessToken
          // Refresh the access token 1 minute before it expires
          runIn((response.data.data.ExpiresIn - 60), loginAws)
        } else {
          // If there's a failure here then purge all session data to force clean slate
          state.session = null
          maybeLogError("login Failure refreshing Token: (%s) %s", response.status, response.data)
          state.status = "Login Error"
        }
      }
    } catch (Exception e) {
      // If there's a failure here then purge all session data to force clean slate
      state.session = null
      maybeLogError("login Error", e)
      state.status = "Login Error"
    }
  } else {
    state.session = null
    try {
      JSONObject jsonBody = new JSONObject()
      jsonBody.put("Email", settings.login)
      jsonBody.put("Password", settings.password)
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
          debug("login Success: (%s), %s", response.status, response.data)
          state.session = [:]
          state.session.accessToken = response.data.data.AccessToken
          state.session.refreshToken = response.data.data.RefreshToken
          // Refresh the access token 1 minute before it expires
          runIn((response.data.data.ExpiresIn - 60), loginAws)
          // Get cookies since this is all new state
          loginCookie()
        } else {
          maybeLogError("login Failure getting Token: (%s) %s", response.status, response.data)
          state.status = "Login Error"
        }
      }
    } catch (Exception e) {
      maybeLogError("login Error", e)
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
        def expiration = null
        response.getHeaders("Set-Cookie").each {
          cookieInfo = it.value.split(";")
          state.session.cookies = state.session.cookies + cookieInfo[0] + ";"
          // find the expires value if it exists
          if (!expiration) {
            for (cookie in cookieInfo) {
              if (cookie.contains("Expires=")) {
                expiration = cookie.split("=")[1]
              }
            }
          }
        }
        Date refreshDate
        if (expiration == null) {
          maybeLogError("No expiration for any cookie found in response: %s", response.getHeaders("Set-Cookie"))
          refreshDate = new Date() + 1
        } else {
          refreshDate = toDateTime(LocalDateTime.parse(expiration,
                  DateTimeFormatter.RFC_1123_DATE_TIME).minusDays(1L).toString() + "Z")
        }
        runOnce(refreshDate, loginCookie)
      } else {
        maybeLogError("login Failure getting Cookie: (%s), %s", response.status, response.data)
        state.status = "Login Error"
      }
    }
  } catch (Exception e) {
    maybeLogError("loginCookie Error", e)
    state.status = "Login Error"
  }
}

void loginOld() {
  debug "Logging in"
  state.session = null
  try {
    JSONObject jsonBody = new JSONObject()
    jsonBody.put("login", settings.login)
    jsonBody.put("password", settings.password)
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
      timeout: 20
    ]
    httpPut(params) { response ->
      if (response.success) {
        debug("login Success: (%s) %s", response.status, response.data)
        state.session = [:]
        state.session.key = response.data.key
        state.session.cookies = ""
        response.getHeaders("Set-Cookie").each {
          state.session.cookies = state.session.cookies + it.value.split(";")[0] + ";"
        }
      } else {
        maybeLogError("login Failure: (%s) %s", response.status, response.data)
        state.status = "Login Error"
      }
    }
  } catch (Exception e) {
    maybeLogError("login Error", e)
    state.status = "Login Error"
  }
}

void login() {
  if ((Boolean) settings.useAwsOAuth) {
    loginAws()
  } else {
    loginOld()
  }
}

/**
 * Adds a PUT HTTP request to the queue with the expectation that it will take approximaly `duration`
 * time to run.  This means other enqueued requests may run after `duration`. 
 * Args may be:
 * body: Map
 * query: Map
 * path: String
 * runAfter: String (name of handler method to run after delay)
 */
@CompileStatic
void httpRequestQueue(Map args, int duration) {
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
void handleRequestQueue(boolean releaseLock = false) {
  if (releaseLock) mutex.release()
  if (requestQueue.isEmpty()) return
  // Get the oldest request in the queue to run.
  try {
    if (!mutex.tryAcquire()) {
      // If we can't obtain the lock it means one of two things:
      // 1. There's an existing operation and we should rightly skip.  In this case,
      //    the last thing the method does is re-run itself so this will clear itself up.
      // 2. There's an unintended failure which has lead to a failed lock release.  We detect
      //    this by checking the last time the lock was held and releasing the mutex if it's
      //    been too long.
      // RACE HERE. if lock time hasnt been updated in this thread yet it will incorrectly move forward
      if ((now() - lastLockTime) > 120000 /* 2 minutes */) {
        // Due to potential race setting and reading the lock time,
        // wait 2s and check again before breaking it
        pauseExecution(2000)
        if ((now() - lastLockTime) > 120000 /* 2 minutes */) {
          warn "HTTP queue lock was held for more than 2 minutes, forcing release"
          mutex.release()
          // In this case we should re-run.
          handleRequestQueue()
        }
      }
      return
    }
    lastLockTime = now()
    Map request = (Map) requestQueue.poll()
    httpRequest(request.path, this.&put, request.body, request.query)

    // Try to process more requests and release the lock since this request
    // should be complete.
    runInMillis((request.duration * 1000), "handleRequestQueue", [data: true])

    // If there was something to run after this then set that up as well.
    if (request.runAfter) {
      runIn(request.duration, request.runAfter)
    }
  } catch(Exception e) {
    maybeLogError("Failed to run HTTP queue", e)
    mutex.release()
  }
}

Map httpRequest(String path, Closure method = this.&get, Map body = null, Map query = null, boolean alreadyTriedRequest = false) {
  Map result = [:]
  Boolean loginState = settings.useAwsOAuth ? !state.session || !state.session.accessToken : !state.session || !state.session.key
  if (loginState) {
    if (alreadyTriedRequest) {
      maybeLogError "Already attempted login but still no session key, giving up"
      return result
    } else {
      login()
      if (settings.useAwsOAuth) {
        loginAws()
      } else {
        login()
      }
      return httpRequest(path, method, body, query, true)
    }
  }
  String payload = body ? new JsonBuilder(body).toString() : null
  Map queryString = settings.useAwsOAuth ? new HashMap() : [_k: state.session.key]
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
      "Cookie": state.session?.cookies,
      "DNT": "1",
      "Accept-Version": SN_APP_VERSION,
      "X-App-Version": SN_APP_VERSION,
    ],
    query: queryString,
    body: payload,
    timeout: 20
  ]
  if ((Boolean) settings.useAwsOAuth) {
    statusParams.headers["Authorization"] = state.session.accessToken
  }
  if (payload) {
    debug("Sending request for %s with query %s: %s", path, queryString, payload)
  } else {
    debug("Sending request for %s with query %s", path, queryString)
  }
  try {
    method(statusParams) { response -> 
      if (response.success) {
        result = response.data
      } else {
        maybeLogError("Failed request for %s %s with payload %s:(%s) %s",
                path, queryString, payload, response.status, response.data)
        state.status = "API Error"
      }
    }
    return result
  } catch (Exception e) {
    if (e.toString().contains("Unauthorized") && !alreadyTriedRequest) {
      // The session is invalid so retry login before giving up.
      info "Unauthorized, retrying login"
      if ((Boolean) settings.useAwsOAuth) {
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
        maybeLogError("Retrying failed request %s\n%s", statusParams, e)
        return httpRequest(path, method, body, query, true)
      } else {
        if (e.toString().contains("Not Found")) {
          // Don't bother polluting logs for Not Found errors as they are likely
          // either intentional (trying to figure out if outlet exists) or a code
          // bug.  In the latter case we still want diagnostic data so we use
          // debug logging.
          debug("Error making request %s\n%s", statusParams, e)
          return result
        }
        maybeLogError("Error making request %s\n%s", statusParams, e)
        state.status = "API Error"
        return result
      }
    }
  }
}

/*------------------ Logging helpers ------------------*/
Boolean okToLogError() {
  String level = (String) settings.logLevel
  if (level != null && level.toInteger() == 0) {
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
  Integer limit = (Integer) settings.limitErrorLogsMin
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
  if (args.length == 1 && args[0] instanceof Exception) {
    logError(msg, (Exception) args[0])
  } else {
    logError(sprintf(msg, args))
  }
}

void debug(String msg, Object... args) {
  String level = (String) settings.logLevel
  if ((Boolean) settings.enableDebugLogging ||
          (level != null && level.toInteger() == 1)) {
    logDebug(sprintf(msg, args))
  }
}

void info(String msg, Object... args) {
  String level = (String) settings.logLevel
  if ((Boolean) settings.enableDebugLogging ||
          level == null ||
          (level.toInteger() >= 1 && level.toInteger() < 3)) {
    logInfo(sprintf(msg, args))
  }
}

void warn(String msg, Object... args) {
  String level = (String) settings.logLevel
  if ((Boolean) settings.enableDebugLogging ||
          level == null ||
          level.toInteger() > 0) {
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

Long now() {
  return (Long) this.delegate.now()
}

/*------------------ Shared constants ------------------*/


@Field static final String appVersion = "3.2.3"  // public version
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

