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
import groovy.transform.Field
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import org.json.JSONObject

@Field static ConcurrentLinkedQueue requestQueue = new ConcurrentLinkedQueue()
@Field static Semaphore mutex = new Semaphore(1)
@Field static Long lastLockTime = 0
@Field static Long lastErrorLogTime = 0

@Field final String DRIVER_NAME = "Sleep Number Bed"
@Field final String NAMESPACE = "rvrolyk"
@Field final String API_HOST = "prod-api.sleepiq.sleepnumber.com"
@Field final String API_URL = "https://" + API_HOST
@Field final String LOGIN_HOST = "l06it26kuh.execute-api.us-east-1.amazonaws.com"
@Field final String LOGIN_URL = "https://" + LOGIN_HOST
@Field final String LOGIN_CLIENT_ID = "jpapgmsdvsh9rikn4ujkodala"
@Field final String USER_AGENT = "SleepIQ/1669639706 CFNetwork/1399 Darwin/22.1.0"
//'''\
//Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36'''

@Field final ArrayList VALID_ACTUATORS = ["H", "F"]
@Field final ArrayList VALID_WARMING_TIMES = [30, 60, 120, 180, 240, 300, 360]
@Field final ArrayList VALID_WARMING_TEMPS = [0, 31, 57, 72]
@Field final ArrayList VALID_PRESET_TIMES = [0, 15, 30, 45, 60, 120, 180]
@Field final ArrayList VALID_PRESETS = [1, 2, 3, 4, 5, 6]
@Field final ArrayList VALID_LIGHT_TIMES = [15, 30, 45, 60, 120, 180]
@Field final ArrayList VALID_LIGHT_BRIGHTNESS = [1, 30, 100]
@Field final Map<String, String> LOG_LEVELS = ["0": "Off", "1": "Debug", "2": "Info", "3": "Warn"]

definition(
  name: "Sleep Number Controller",
  namespace: "rvrolyk",
  author: "Russ Vrolyk",
  description: "Control your Sleep Number Flexfit bed.",
  category: "Integrations",
  iconUrl: "",
  iconX2Url: "",
  importUrl: "https://github.com/rvrolyk/SleepNumberController/blob/master/SleepNumberController_App.groovy"
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
    state.paused = !state.paused
    if (state.paused) {
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

  dynamicPage(name: "homePage") {
    if (state.paused) {
      state.pauseButtonName = "Resume"
    } else {
      state.pauseButtonName = "Pause"
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
      def defaultVariableRefresh = settings.variableRefresh != null && !settings.variableRefresh ? false : settings.refreshInterval == null
      input "variableRefresh", "bool", title: "Use variable refresh interval? (recommended)", defaultValue: defaultVariableRefresh,
         submitOnChange: true
      if (defaultVariableRefresh || settings.variableRefresh) {
        input name: "dayInterval", type: "number", title: "Daytime Refresh Interval (minutes; 0-59)",
            description: "How often to refresh bed state during the day", defaultValue: 30
        input name: "nightInterval", type: "number", title: "Nighttime Refresh Interval (minutes; 0-59)",
              description: "How often to refresh bed state during the night", defaultValue: 1
        input "variableRefreshModes", "bool", title: "Use modes to control variable refresh interval", defaultValue: false, submitOnChange: true
        if (settings.variableRefreshModes) {
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
      if (!settings.login || !settings.password) {
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
        if (settings.login && settings.password) {
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
      if (state.displayName) {
        defaultName = state.displayName
        app.updateLabel(defaultName)
      }
      label title: "Assign an app name", required: false, defaultValue: defaultName
      input name: "modes", type: "mode", title: "Set for specific mode(s)", required: false, multiple: true, submitOnChange: true
      input name: "switchToDisable", type: "capability.switch", title: "Switch to disable refreshes", required: false, submitOnChange: true
      input "enableDebugLogging", "bool", title: "Enable debug logging for 30m?", defaultValue: false, required: true, submitOnChange: true
      input "logLevel", "enum", title: "Choose the logging level", defaultValue: "2", submitOnChange: true, options: LOG_LEVELS
      input "limitErrorLogsMin", "number", title: "How often to allow error logs (minutes), 0 for all the time. <br><font size=-1>(Only applies when log level is not off)</font> ", defaultValue: 0, submitOnChange: true 
      if (settings.login && settings.password) {
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
  if (enableDebugLogging) {
    runIn(1800, logsOff)
  }
}

void logsOff() {
  if (enableDebugLogging) {
    // Log this information regardless of user setting.
    log.info "debug logging disabled..."
    app.updateSetting "enableDebugLogging", [value: "false", type: "bool"]
  }
}

def initialize() {
  if (settings.refreshInterval <= 0 && !settings.variableRefresh) {
    log.error "Invalid refresh interval ${settings.refreshInterval}"
  }
  if (settings.variableRefresh && (settings.dayInterval <= 0 || settings.nightInterval <= 0)) {
    log.error "Invalid refresh intervals ${settings.dayInterval} or ${settings.nightInterval}"
  }
  if (settings.variableRefreshModes) {
    subscribe(location, "mode", configureVariableRefreshInterval)
  }
  setRefreshInterval(0 /* force picking from settings */, "" /* ignored */)
  initializeBedInfo()
  refreshChildDevices()
  updateLabel()
}

void updateLabel() {
  // Store the user's original label in state.displayName
  if (!app.label.contains("<span") && state?.displayName != app.label) {
    state.displayName = app.label
  }
  if (state?.status || state?.paused) {
    def status = state?.status
    String label = "${state.displayName} <span style=color:"
    if (state?.paused) {
      status = "(Paused)"
      label += "red"
    } else if (state.status == "Online") {
      label += "green"
    } else if (state.status.contains("Login")) {
      label += "red"
    } else {
      label += "orange"
    }
    label += ">${status}</span>"
    app.updateLabel(label)
  }
}

void initializeBedInfo() {
  debug "Setting up bed info"
  def bedInfo = getBeds()
  state.bedInfo = [:]
  bedInfo.beds.each() { Map bed ->
    debug "Bed id ${bed.bedId}"
    if (!state.bedInfo.containsKey(bed.bedId)) {
      state.bedInfo[bed.bedId] = [:]
    }
    def components = []
    for (def component : bed.components) {
      if (component.type == "Base"
          && component.model.toLowerCase().contains("integrated")) {
        // Integrated bases need to be treated separately as they don't appear to have
        // foundation status endpoints so don't lump this with a base type directly.
        components << "Integrated Base"
      } else {
        components << component.type
      }
    }
    state.bedInfo[bed.bedId].components = components
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
    def side = device.getState().side
    def bedId = device.getState().bedId
    def type = device.getState()?.type ?: "Parent"

    output << [
      name: device.label,
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
  List data = getBedDeviceData()
  Set typeList = data.collect { if ((String)it.bedId == bedId) { return it.type } }

  // cull NULL entries
  typeList = typeList.findAll()

  return typeList
}

// Use with #schedule as apparently it's not good to mix #runIn method call
// and #schedule method call.
void scheduledRefreshChildDevices() {
  refreshChildDevices()
  if (settings.variableRefresh) {
    // If we're using variable refresh then try to reconfigure it since bed states
    // have been updated and we may be in daytime.
    configureVariableRefreshInterval()
  }
}

void refreshChildDevices() {
  // Only refresh if mode is a selected one
  if (settings.modes && !settings.modes.contains(location.mode)) {
    debug "Skipping refresh, not the right mode"
    return
  }
  // If there's a switch defined and it's on, don't bother refreshing at all
  if (settings.switchToDisable && settings.switchToDisable.currentValue("switch") == "on") {
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
  debug "setRefreshInterval(${val})"
  def random = new Random()
  Integer randomInt = random.nextInt(40) + 4
  if (val && val > 0) {
    schedule("${randomInt} /${val} * * * ?", "scheduledRefreshChildDevices")
  } else {
    if (!settings.variableRefresh) {
      debug "Resetting interval to ${settings.refreshInterval}"
      schedule("${randomInt} /${settings.refreshInterval} * * * ?", "scheduledRefreshChildDevices")
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
  boolean night = false

  if (settings.variableRefreshModes) {
    if (settings.nightMode.contains(location.mode)) {
      night = true
    } else {
      night = false
    }
  } else {
    // Gather presence state of all child devices
    List presentChildren = getBedDevices().findAll {
      (!it.getState().type || it.getState()?.type == "presence") && it.isPresent()
    }
    Date now = new Date()
    if (timeOfDayIsBetween(toDateTime(settings.dayStart), toDateTime(settings.nightStart), now)) {
      if (presentChildren.size() > 0) return // if someone is still in bed, don't change anything
      night = false
    } else {
      night = true
    }
  }

  Random random = new Random()
  Integer randomInt = random.nextInt(40) + 4

  if (night) {
    // Don't bother setting the schedule if we are already set to night.
    if (state.variableRefresh != "night") {
      info "Setting interval to night. Refreshing every ${settings.nightInterval} minutes."
      schedule("${randomInt} /${settings.nightInterval} * * * ?", "scheduledRefreshChildDevices")
      state.variableRefresh = "night"
    }
  } else if (state.variableRefresh != "day") {
    info "Setting interval to day. Refreshing every ${settings.dayInterval} minutes."
    schedule("${randomInt} /${settings.dayInterval} * * * ?", "scheduledRefreshChildDevices")
    state.variableRefresh = "day"
  }
}

def findBedPage() {
  def responseData = getBedData()
  List devices = getBedDevices()
  def childDevices = []
  dynamicPage(name: "findBedPage") {
    if (responseData && responseData.beds.size() > 0) {
      responseData.beds.each { bed ->
        def sidesSeen = []
        section("Bed: ${bed.bedId}") {
          paragraph "<br>Note: <i>Sides are labeled as if you area laying in bed.</i>"
          if (devices.size() > 0) {
            for (def dev : devices) {
              if (dev.getState().bedId != bed.bedId) {
                debug "bedId's don't match, skipping"
                continue
              }
              if (!dev.getState().type || dev.getState()?.type == "presence") {
                if (!dev.getState().type) {
                  childDevices << dev.getState().side
                }
                sidesSeen << dev.getState().side
                href "selectBedPage", name: "Bed: ${bed.bedId}", title: dev.label, description: "Click to modify",
                    params: [bedId: bed.bedId, side: dev.getState().side, label: dev.label]
              }
            }
            if (childDevices.size() < 2) {
              input "createNewChildDevices", "bool", title: "Create new child device types", defaultValue: false, submitOnChange: true
              if (settings.createNewChildDevices) {
                if (!childDevices.contains("Left")) {
                  href "selectBedPage", name: "Bed: ${bed.bedId}", title: "Left Side", description: "Click to create",
                      params: [bedId: bed.bedId, side: "Left", label: ""]
                }
                if (!childDevices.contains("Right")) {
                  href "selectBedPage", name: "Bed: ${bed.bedId}", title: "Right Side", description: "Click to create",
                      params: [bedId: bed.bedId, side: "Right", label: ""]
                }
              }
            }
          }
          if (!sidesSeen.contains("Left")) {
            href "selectBedPage", name: "Bed: ${bed.bedId}", title: "Left Side", description: "Click to create",
                params: [bedId: bed.bedId, side: "Left", label: ""]
          }
          if (!sidesSeen.contains("Right")) {
            href "selectBedPage", name: "Bed: ${bed.bedId}", title: "Right Side", description: "Click to create",
                params: [bedId: bed.bedId, side: "Right", label: ""]
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

String presenceText(presence) {
  return presence ? "Present" : "Not Present"
}

def selectBedPage(params) {
  initializeBedInfo()
  app.updateSetting("newDeviceName", "")
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
      def name = settings.newDeviceName?.trim() ? settings.newDeviceName : params.label?.trim() ? params.label : newDeviceName
      input "newDeviceName", "text", title: "Device Name", defaultValue: name,
          description: "What prefix do you want for the devices?", submitOnChange: true,
          required: true
      input "useChildDevices", "bool", title: "Use child devices? (recommended)", defaultValue: true,
         submitOnChange: true
      if (!settings.useChildDevices) {
        input "useContainer", "bool", title: "Use virtual container?", defaultValue: false,
           submitOnChange: true
      }
      paragraph "A presence type device exposes on/off as switching to a preset level (on) and flat (off).  Dimming will change the Sleep Number."
      if (settings.useChildDevices) {
        paragraph "This is the parent device when child devices are used"
        settings.createPresence = true
      } else {
        input "createPresence", "bool",
            title: "Create presence device for ${params.side.toLowerCase()} side?",
            defaultValue: true, submitOnChange: true
      }
      paragraph "A head type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the head position (0 is flat, 100 is fully raised)."
      input "createHeadControl", "bool",
         title: "Create device to control the head of the ${params.side.toLowerCase()} side?",
         defaultValue: true, submitOnChange: true
      paragraph "A foot type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the foot position (0 is flat, 100 is fully raised)."
      input "createFootControl", "bool",
         title: "Create device to control the foot of the ${params.side.toLowerCase()} side?",
         defaultValue: true, submitOnChange: true
      if (state.bedInfo[params.bedId].components.contains("Warming")) {
        paragraph "A foot type device exposes on/off as switching the foot warming on or off.  Dimming will change the heat levels (1: low, 2: medium, 3: high)."
        input "createFootWarmer", "bool",
           title: "Create device to control the foot warmer of the ${params.side.toLowerCase()} side?",
           defaultValue: true, submitOnChange: true
      }
      if (settings.useChildDevices) {
        determineUnderbedLightSetup(params.bedId)
        paragraph "Underbed lighting creates a dimmer allowing the light to be turned on or off at different levels with timer based on parent device preference."
        input "createUnderbedLighting", "bool",
         title: "Create device to control the underbed lighting of the ${params.side.toLowerCase()} side?",
           defaultValue: false, submitOnChange: true
        if (state.bedInfo[params.bedId].outlets.size > 1) {
          paragraph "Outlet creates a switch allowing foundation outlet for this side to be turned on or off."
          input "createOutlet", "bool",
           title: "Create device to control the outlet of the ${params.side.toLowerCase()} side?",
             defaultValue: false, submitOnChange: true
        }
      }
    }
    if (!newDeviceName?.trim()) {
      debug "no device name entered, skipping create/modify section"
    } else {
      section {
        String msg = "Will create the following devices"
        def containerName = ""
        def types = []
        if (settings.useChildDevices) {
          settings.useContainer = false
          msg += " with each side as a primary device and each type as a child device of the side"
        } else if (settings.useContainer) {
          containerName = "${newDeviceName} Container"
          msg += " in virtual container '${containerName}'"
        }
        msg += ":<ol>"
        if (settings.createPresence) {
          msg += "<li>${createDeviceLabel(newDeviceName, 'presence')}</li>"
          types.add("presence")
        }
        if (settings.createHeadControl) {
          msg += "<li>${createDeviceLabel(newDeviceName, 'head')}</li>"
          types.add("head")
        }
        if (settings.createFootControl) {
          msg += "<li>${createDeviceLabel(newDeviceName, 'foot')}</li>"
          types.add("foot")
        }
        if (settings.createFootWarmer) {
          msg += "<li>${createDeviceLabel(newDeviceName, 'foot warmer')}</li>"
          types.add("foot warmer")
        }
        if (settings.createUnderbedLighting && settings.useChildDevices) {
          msg += "<li>${createDeviceLabel(newDeviceName, 'underbed light')}</li>"
          types.add("underbed light")
        }
        if (settings.createOutlet && settings.useChildDevices) {
          msg += "<li>${createDeviceLabel(newDeviceName, 'outlet')}</li>"
          types.add("outlet")
        }
        msg += "</ol>"
        paragraph msg
        newDeviceName = ""
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
}

String createDeviceLabel(String name, String type) {
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

def createBedPage(params) {
  def container = null
  if (params.useContainer) {
    container = createContainer(params.bedId, params.containerName, params.side)
  }
  List existingDevices = getBedDevices()
  List devices = []
  // TODO: Consider allowing more than one identical device for debug purposes.
  if (params.useChildDevices) {
    // Bed Ids seem to always be negative so convert to positive for the device
    // id for better formatting.
    def bedId = Math.abs(Long.valueOf(params.bedId))
    def deviceId = "sleepnumber.${bedId}.${params.side}"
    def label = createDeviceLabel(settings.newDeviceName, "presence")
    def parent = existingDevices.find{ it.deviceNetworkId == deviceId }
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
    params.types.each { type ->
      if (type != "presence") {
        def childId = deviceId + "-" + type.replaceAll(" ", "")
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
        def newDevice = parent.createChildDevice(childId, "Generic Component ${driverType}",
            createDeviceLabel(settings.newDeviceName, type))
        if (newDevice) {
          devices.add(newDevice)
        }
      }
    }
  } else {
    params.types.each { type ->
      def deviceId = "sleepnumber.${params.bedId}.${params.side}.${type.replaceAll(' ', '_')}"
      if (existingDevices.find{ it.data.vcId == deviceId }) {
        info "Not creating device ${deviceId}, it already exists"
      } else {
        def label = createDeviceLabel(settings.newDeviceName, type)
        def device = null
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
  settings.newDeviceName = null
  dynamicPage(name: "selectDevicePage") {
    section {
      def header = "Created new devices"
      if (params.useChildDevices) {
        header += " using child devices"
      } else if (params.useContainer) {
        header += " in container ${params.containerName}"
      }
      header += ":"
      paragraph(header)
      def displayInfo = "<ol>"
      devices.each { device ->
        displayInfo += "<li>"
        displayInfo += "${device.label}"
        if (!params.useChildDevices) {
          displayInfo += "<br>Bed ID: ${device.getState().bedId}"
          displayInfo += "<br>Side: ${device.getState().side}"
          displayInfo += "<br>Type: ${device.getState()?.type}"
        }
        displayInfo += "</li>"
      }
      displayInfo += "</ol>"
      paragraph displayInfo
    }
    section {
      href "findBedPage", title: "Back to Bed List", description: null
    }
  }
}

def diagnosticsPage(params) {
  def bedInfo = getBeds()
  dynamicPage(name: "diagnosticsPage") {
    bedInfo.beds.each { Map bed ->
      section("Bed: ${bed.bedId}") {
        def bedOutput = "<ul>"
        bedOutput += "<li>Size: ${bed.size}"
        bedOutput += "<li>Dual Sleep: ${bed.dualSleep}"
        bedOutput += "<li>Components:"
        for (def component : bed.components) {
          bedOutput += "<ul>"
          bedOutput += "<li>Type: ${component.type}"
          bedOutput += "<li>Status: ${component.status}"
          bedOutput += "<li>Model: ${component.model}"
          bedOutput += "</ul>"
        }
        paragraph bedOutput
      }
    }
    section("Send Requests") {
        input "requestType", "enum", title: "Request type", options: ["PUT", "GET"]
        input "requestPath", "text", title: "Request path", description: "Full path including bed id if needed"
        input "requestBody", "text", title: "Request Body in JSON"
        input "requestQuery", "text", title: "Extra query key/value pairs in JSON"
        href "diagnosticsPage", title: "Send request", description: null, params: [
          requestType: requestType,
          requestPath: requestPath,
          requestBody: requestBody,
          requestQuery: requestQuery
        ]
        if (params && params.requestPath && params.requestType) {
          Map body
          if (params.requestBody) {
            try {
              body = parseJson(params.requestBody)
            } catch (groovy.json.JsonException e) {
              maybeLogError "${params.requestBody} : ${e}"
            }
          }
          Map query
          if (params.requestQuery) {
            try {
              query = parseJson(params.requestQuery)
            } catch (groovy.json.JsonException e) {
              maybeLogError "${params.requestQuery} : ${e}"
            }
          }
          def response = httpRequest((String)params.requestPath,
                                     requestType == "PUT" ? this.&put : this.&get,
                                     body,
                                     query,
                                     true)
          paragraph "${response}"
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

/**
 * Creates a virtual container with the given name and side
 */
def createContainer(String bedId, String containerName, String side) {
  def container = getChildDevices().find{it.typeName == "Virtual Container" && it.label == containerName}
  if(!container) {
    debug "Creating container ${containerName}"
    try {
      container = addChildDevice("stephack", "Virtual Container", "${app.id}.${bedId}.${side}", null,
          [name: containerName, label: containerName, completedSetup: true]) 
    } catch (e) {
      log.error "Container device creation failed with error = ${e}"
      return null
    }
  }
  return container
}

def getBedData() {
  def responseData = getFamilyStatus()
  processBedData(responseData)
  return responseData
}

/**
 * Updates the bed devices with the given data.
 */
def processBedData(Map responseData) {
  if (!responseData || responseData.size() == 0) {
    debug "Empty response data"
    return
  }
  debug "Response data from SleepNumber: ${responseData}"
  // cache for foundation status per bed id so we don't have to run the api call N times
  def foundationStatus = [:]
  def footwarmingStatus = [:]
  def privacyStatus = [:]
  def bedFailures = [:]
  def loggedError = [:]
  def sleepNumberFavorites = [:]
  def outletData = [:]
  def underbedLightData = [:]
  def responsiveAir = [:]

  for (def device : getBedDevices()) {
    String bedId = device.getState().bedId.toString()
    String bedSideStr = device.getState().side
    if (!outletData.get(bedId)) {
      outletData[bedId] = [:]
      underbedLightData[bedId] = [:]
    }

    Set<String> deviceTypes = getBedDeviceTypes(bedId)
    for (def bed : (List)responseData.beds) {
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

        def bedSide = bedSideStr == "Right" ? bed.rightSide : bed.leftSide
        device.setPresence(bedSide.isInBed)
        def statusMap = [
          sleepNumber: bedSide.sleepNumber,
          privacyMode: privacyStatus[bedId],
        ]
        if (underbedLightData.get(bedId)) {
          Integer outletNumber = bedSideStr == "Left" ? 3 : 4
          String bstate = underbedLightData[bedId]?.enableAuto ? "Auto" :
              outletData[bedId][outletNumber]?.setting == 1 ? "On" : "Off"
          String timer = bstate == "Auto" ? "Not set" :
              outletData[bedId][outletNumber]?.timer ? outletData[bedId][outletNumber]?.timer : "Forever"
          def brightness = underbedLightData[bedId]?."fs${bedSideStr}UnderbedLightPWM"
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
          def headPosition = convertHexToNumber(foundationStatus.get(bedId)."fs${bedSideStr}HeadPosition")
          def footPosition = convertHexToNumber(foundationStatus.get(bed.bedId)."fs${bedSideStr}FootPosition")
          def bedPreset = foundationStatus.get(bedId)."fsCurrentPositionPreset${bedSideStr}"
          // There's also a MSB timer but not sure when that gets set.  Least significant bit seems used for all valid times.
          def positionTimer = convertHexToNumber(foundationStatus.get(bedId)."fs${bedSideStr}PositionTimerLSB")
          statusMap << [
            headPosition: headPosition,
            footPosition:  footPosition,
            positionPreset: bedPreset,
            positionPresetTimer: foundationStatus.get(bedId)."fsTimerPositionPreset${bedSideStr}",
            positionTimer: positionTimer
          ]
        } else if (!loggedError.get(bedId)) {
          debug "Not updating foundation state, " + (bedFailures.get(bedId) ? "error making requests" : "no data")
        }
        if (footwarmingStatus.get(bedId)) {
          statusMap << [
            footWarmingTemp: footwarmingStatus.get(bedId)."footWarmingStatus${bedSideStr}",
            footWarmingTimer: footwarmingStatus.get(bedId)."footWarmingTimer${bedSideStr}",
          ]
        } else if (!loggedError.get(bedId)) {
          debug "Not updating footwarming state, " + (bedFailures.get(bedId) ? "error making requests" : "no data")
        }
        if (!sleepNumberFavorites.get(bedId)) {
          sleepNumberFavorites[bedId] = getSleepNumberFavorite(bedId)
        }
        def favorite = sleepNumberFavorites.get(bedId).get("sleepNumberFavorite" + bedSideStr, -1)
        if (favorite >= 0) {
          statusMap << [
            sleepNumberFavorite: favorite
          ]
        }
        // If the device has responsive air, fetch that status and add to the map
        if (!bedFailures.get(bedId) && device.getSetting('enableResponsiveAir')) {
          if (!responsiveAir.get(bedId)) {
            responsiveAir[bedId] = getResponsiveAirStatus(bedId)
          }
          def side = bedSideStr.toLowerCase()
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
  debug "Cached data: ${foundationStatus}\n${footwarmingStatus}"
}

def convertHexToNumber(value) {
  if (value == "" || value == null) return 0
  try {
    return Integer.parseInt(value, 16)
  } catch (Exception e) {
    log.error "Failed to convert non-numeric value ${value}: ${e}"
    return value
  }
}

def getBeds() {
  debug "Getting information for all beds"
  return httpRequest("/rest/bed")
}

def getFamilyStatus() {
  debug "Getting family status"
  return httpRequest("/rest/bed/familyStatus")
}

def getFoundationStatus(String bedId, String currentSide) {
  debug "Getting Foundation Status for ${bedId} / ${currentSide}"
  return httpRequest("/rest/bed/${bedId}/foundation/status")
}

def getFootWarmingStatus(String bedId) {
  debug "Getting Foot Warming Status for ${bedId}"
  return httpRequest("/rest/bed/${bedId}/foundation/footwarming")
}

def getResponsiveAirStatus(String bedId) {
  debug "Getting responsive air status for ${bedId}"
  return httpRequest("/rest/bed/${bedId}/responsiveAir")
}

def setResponsiveAirState(Boolean state, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  Map body = [:] 
  String side = device.getState().side
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
  httpRequestQueue(5, path: "/rest/bed/${device.getState().bedId}/responsiveAir",
      body: body, runAfter: "refreshChildDevices")
}

/**
 * Params must be a Map containing keys actuator and position.
 * The side is derived from the specified device.
 */
void setFoundationAdjustment(Map params, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  if (!params?.actuator || params?.position == null) {
    log.error "Missing param values, actuator and position are required"
    return
  }
  if (!VALID_ACTUATORS.contains(params.actuator)) {
    log.error "Invalid actuator ${params.actuator}, valid values are ${VALID_ACTUATORS}"
    return
  }
  Map body = [
    speed: 0,
    actuator: params.actuator,
    side: device.getState().side[0],
    position: params.position
  ]
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // The timing appears to be linear which means it's 0.35 seconds per level adjusted for the head and 0.18
  // for the foot.
  int currentPosition = params.actuator == "H" ? device.currentValue("headPosition") : device.currentValue("footPosition")
  int positionDelta = Math.abs(params.position - currentPosition)
  float movementDuration = params.actuator == "H" ? 0.35 : 0.18
  int waitTime = Math.round(movementDuration * positionDelta) + 1
  httpRequestQueue(waitTime, path: "/rest/bed/${device.getState().bedId}/foundation/adjustment/micro",
      body: body, runAfter: "refreshChildDevices")
}

/**
 * Params must be a Map containing keys temp and timer.
 * The side is derived from the specified device.
 */
void setFootWarmingState(Map params, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  if (params?.temp == null || params?.timer == null) {
    log.error "Missing param values, temp and timer are required"
    return
  }
  if (!VALID_WARMING_TIMES.contains(params.timer)) {
    log.error "Invalid warming time ${params.timer}, valid values are ${VALID_WARMING_TIMES}"
    return
  }
  if (!VALID_WARMING_TEMPS.contains(params.temp)) {
    log.error "Invalid warming temp ${params.temp}, valid values are ${VALID_WARMING_TEMPS}"
    return
  }
  Map body = [
    "footWarmingTemp${device.getState().side}": params.temp,
    "footWarmingTimer${device.getState().side}": params.timer
  ]
  // Shouldn't take too long for the bed to reflect the new state, wait 5s just to be safe
  httpRequestQueue(5, path: "/rest/bed/${device.getState().bedId}/foundation/footwarming",
      body: body, runAfter: "refreshChildDevices")
}

/**
 * Params must be a map containing keys preset and timer.
 * The side is derived from the specified device.
 */
def setFoundationTimer(Map params, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  if (params?.preset == null || params?.timer == null) {
    log.error "Missing param values, preset and timer are required"
    return
  }
  if (!VALID_PRESETS.contains(params.preset)) {
    log.error "Invalid preset ${params.preset}, valid values are ${VALID_PRESETS}"
    return
  }
  if (!VALID_PRESET_TIMES.contains(params.timer)) {
    log.error "Invalid timer ${params.timer}, valid values are ${VALID_PRESET_TIMES}"
    return
  }
  Map body = [
    side: device.getState().side[0],
    positionPreset: params.preset,
    positionTimer: params.timer
  ]
  httpRequest("/rest/bed/${device.getState().bedId}/foundation/adjustment", this.&put, body)
  // Shouldn't take too long for the bed to reflect the new state, wait 5s just to be safe
  runIn(5, "refreshChildDevices")
}

/**
 * The side is derived from the specified device.
 */
def setFoundationPreset(Integer preset, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  if (!VALID_PRESETS.contains(preset)) {
    log.error "Invalid preset ${preset}, valid values are ${VALID_PRESETS}"
    return
  }
  Map body = [
    speed: 0,
    preset : preset,
    side: device.getState().side[0],
  ]
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // Rather than attempt to derive the preset relative to the current state so we can compute
  // the time (as we do for adjustment), we just use the maximum.
  httpRequestQueue(35, path: "/rest/bed/${device.getState().bedId}/foundation/preset",
      body: body, runAfter: "refreshChildDevices")
}

def stopFoundationMovement(Map ignored, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  Map body = [
    massageMotion: 0,
    headMotion: 1,
    footMotion: 1,
    side: device.getState().side[0],
  ]
  httpRequest("/rest/bed/${device.getState().bedId}/foundation/motion", this.&put, body)
  runIn(5, "refreshChildDevices")
}

/**
 * The side is derived from the specified device.
 */
def setSleepNumber(BigDecimal number, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }

  Map body = [
    bedId: device.getState().bedId,
    sleepNumber: number,
    side: device.getState().side[0]
  ]
  // Not sure how long it takes to inflate or deflate so just wait 20s
  httpRequestQueue(20, path: "/rest/bed/${device.getState().bedId}/sleepNumber",
      body: body, runAfter: "refreshChildDevices") 
}

def getPrivacyMode(String bedId) {
  debug "Getting Privacy Mode for ${bedId}"
  return httpRequest("/rest/bed/${bedId}/pauseMode", this.&get)?.pauseMode
}

def setPrivacyMode(Boolean mode, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  def pauseMode = mode ? "on" : "off"
  // Cloud request so no need to queue.
  httpRequest("/rest/bed/${device.getState().bedId}/pauseMode", this.&put, null, [mode: pauseMode])
  runIn(2, "refreshChildDevices")
}

def getSleepNumberFavorite(String bedId) {
  debug "Getting Sleep Number Favorites"
  return httpRequest("/rest/bed/${bedId}/sleepNumberFavorite", this.&get)
}

def setSleepNumberFavorite(String ignored, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  // Get the favorite for the device first, the most recent poll should be accurate
  // enough.
  def favorite = device.currentValue("sleepNumberFavorite")
  debug "sleep number favorite for ${device.getState().side} is ${favorite}"
  if (!favorite || favorite < 0) {
    log.error "Unable to determine sleep number favorite for side ${device.getState().side}"
    return
  }
  if (device.currentValue("sleepNumber") == favorite) {
    debug "Already at favorite"
    return
  }
  setSleepNumber(favorite, devId)
}

def getOutletState(String bedId, Integer outlet) {
  return httpRequest("/rest/bed/${bedId}/foundation/outlet",
        this.&get, null, [outletId: outlet])
}

def setOutletState(String outletState, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  if (!outletState) {
    log.error "Missing outletState"
    return
  }
  def outletNum = device.getState().side == "Left" ? 1 : 2
  setOutletState(device.getState().bedId, outletNum, outletState)
}

/**
 * Sets the state of the given outlet.
 * @param bedId: the bed id
 * @param outletId: 1-4
 * @param state: on or off
 * @param timer: a valid minute duration (for outlets 3 and 4 only)
 * Timer is the only optional parameter.
 */
def setOutletState(String bedId, Integer outletId, String outletState, Integer timer = null) {
  if (!bedId || !outletId || !outletState) {
    log.error "Not all required arguments present"
    return
  }

  if (timer && !VALID_LIGHT_TIMES.contains(timer)) {
    log.error "Invalid underbed light timer ${timer}.  Valid values are ${VALID_LIGHT_TIMES}"
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

def getUnderbedLightState(String bedId) {
  httpRequest("/rest/bed/${bedId}/foundation/underbedLight", this.&get)
}

def getUnderbedLightBrightness(String bedId) {
  determineUnderbedLightSetup(bedId)
  def brightness = httpRequest("/rest/bed/${bedId}/foundation/system", this.&get)
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
def determineUnderbedLightSetup(String bedId) {
  if (!state.bedInfo[bedId].outlets) {
    debug "Determining underbed lighting outlets for ${bedId}"
    // Determine if this bed has 1 or 2 underbed lighting outlets and store for future use.
    def outlet3 = getOutletState(bedId, 3)
    def outlet4 = getOutletState(bedId, 4)
    def outlets = []
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
def setUnderbedLightState(Map params, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }

  if (!params.state) {
    log.error "Missing param state"
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
    log.error "Invalid underbed light brightness ${params.brightness}. Valid values are ${VALID_LIGHT_BRIGHTNESS}"
    return
  }

  // First set the light state.
  Map body = [
    enableAuto: params.state == "auto"
  ]
  httpRequest("/rest/bed/${device.getState().bedId}/foundation/underbedLight", this.&put, body)

  determineUnderbedLightSetup(device.getState().bedId)
  def rightBrightness = params.brightness
  def leftBrightness = params.brightness
  def outletNum = 3
  if (state.bedInfo[device.getState().bedId].outlets.size() > 1) {
    // Two outlets so set the side corresponding to the device rather than
    // defaulting to 3 (which should be a single light)
    if (device.getState().side == "Left") {
      outletNum = 3
      rightBrightness = null
      leftBrightness = params.brightness
    } else {
      outletNum = 4
      rightBrightness = params.brightness
      leftBrightness = null
    }
  }
  setOutletState(device.getState().bedId, outletNum,
      params.state == "auto" ? "off" : params.state, params.timer)

  // If brightness was given then set it.
  if (params.brightness) {
    body = [
      rightUnderbedLightPWM: rightBrightness,
      leftUnderbedLightPWM: leftBrightness
    ]
    httpRequest("/rest/bed/${device.getState().bedId}/foundation/system", this.&put, body)
  }
  runIn(10, "refreshChildDevices") 
}


Map getSleepData(Map ignored, String devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  def bedId = device.getState().bedId
  def ids = [:]
  // We need a sleeper id for the side in order to look up sleep data.
  // Get sleeper to get list of sleeper ids
  debug "Getting sleeper ids for ${bedId}"
  def sleepers = httpRequest("/rest/sleeper", this.&get)
  sleepers.sleepers.each() { sleeper ->
    if (sleeper.bedId == bedId) {
      def side
      switch (sleeper.side) {
        case 0:
          side = "Left"
          break
        case 1:
          side = "Right"
          break
        default:
          warn "Unknown sleeper info: ${sleeper}"
      }
      if (side) {
        ids[side] = sleeper.sleeperId
      }
    }
  }

  debug "Getting sleep data for ${ids[device.getState().side]}"
  // Interval can be W1 for a week, D1 for a day and M1 for a month.
  return httpRequest("/rest/sleepData", this.&get, null, [
      interval: "D1",
      sleeper: ids[device.getState().side],
      includeSlices: false,
      date: new Date().format("yyyy-MM-dd'T'HH:mm:ss")
  ])
}

void loginAws() {
  debug "Logging in"
  if (state.session?.refreshToken) {
    state.session.accessToken = null
    try {
      JSONObject jsonBody = new JSONObject();
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
        def refreshDate = null
        if (expiration == null) {
          maybeLogError "No expiration for any cookie found in response: " + response.getHeaders("Set-Cookie")
          refreshDate = new Date() + 1
        } else {
          refreshDate = toDateTime(java.time.LocalDateTime.parse(expiration,
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).minusDays(1L).toString() + "Z")
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
        debug "login Success: (${response.status}) ${response.data}"
        state.session = [:]
        state.session.key = response.data.key
        state.session.cookies = ""
        response.getHeaders("Set-Cookie").each {
          state.session.cookies = state.session.cookies + it.value.split(";")[0] + ";"
        }
      } else {
        maybeLogError "login Failure: (${response.status}) ${response.data}"
        state.status = "Login Error"
      }
    }
  } catch (Exception e) {
    maybeLogError "login Error: ${e}"
    state.status = "Login Error"
  }
}

void login() {
  if (settings.useAwsOAuth) {
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
      // RACE HERE. if lock time hasnt been updsted in this thread yet it will incorrectly move forward
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
    Map request = requestQueue.poll()
    httpRequest(request.path, this.&put, request.body, request.query)

    // Try to process more requests and release the lock since this request
    // should be complete.
    runInMillis((request.duration * 1000), "handleRequestQueue", [data: true])

    // If there was something to run after this then set that up as well.
    if (request.runAfter) {
      runIn(request.duration, request.runAfter)
    }
  } catch(e) {
    maybeLogError "Failed to run HTTP queue: ${e}"
    mutex.release()
  }
}

def httpRequest(String path, Closure method = this.&get, Map body = null, Map query = null, boolean alreadyTriedRequest = false) {
  def result = [:]
  def loginState = settings.useAwsOAuth ? !state.session || !state.session.accessToken : !state.session || !state.session.key
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
  def payload = body ? new groovy.json.JsonBuilder(body).toString() : null
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
      "Accept-Version": "4.4.1",
      "X-App-Version": "4.4.1",
    ],
    query: queryString,
    body: payload,
    timeout: 20
  ]
  if (settings.useAwsOAuth) {
    statusParams.headers["Authorization"] = state.session.accessToken
  }
  if (payload) {
    debug "Sending request for ${path} with query ${queryString}: ${payload}"
  } else {
    debug "Sending request for ${path} with query ${queryString}"
  }
  try {
    method(statusParams) { response -> 
      if (response.success) {
        result = response.data
      } else {
        maybeLogError "Failed request for ${path} ${queryString} with payload ${payload}:(${response.status}) ${response.data}"
        state.status = "API Error"
      }
    }
    return result
  } catch (Exception e) {
    if (e.toString().contains("Unauthorized") && !alreadyTriedRequest) {
      // The session is invalid so retry login before giving up.
      info "Unauthorized, retrying login"
      if (settings.useAwsOAuth) {
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
        maybeLogError "Retrying failed request ${statusParams}\n${e}"
        return httpRequest(path, method, body, query, true)
      } else {
        if (e.toString().contains("Not Found")) {
          // Don't bother polluting logs for Not Found errors as they are likely
          // either intentional (trying to figure out if outlet exists) or a code
          // bug.  In the latter case we still want diagnostic data so we use
          // debug logging.
          debug "Error making request ${statusParams}\n${e}"
          return result
        }
        maybeLogError "Error making request ${statusParams}\n${e}"
        state.status = "API Error"
        return result
      }
    }
  }
}

/**
 * Only logs an error message if one wasn't logged within the last
 * N minutes where N is configurable.
 */
void maybeLogError(String msg) {
  if (logLevel != null && logLevel.toInteger() == 0) {
    return
  }
  if (!settings.limitErrorLogsMin /* off */
      || (now() - lastErrorLogTime) > (settings.limitErrorLogsMin * 60 * 1000)) {
    log.error msg
    lastErrorLogTime = now()
  }
}

void debug(String msg) {
  if (enableDebugLogging || (logLevel != null && logLevel.toInteger() == 1)) {
    log.debug msg
  }
}

void info(String msg) {
  if (enableDebugLogging || logLevel == null
      || (logLevel.toInteger() >= 1 && logLevel.toInteger() < 3)) {
    log.info msg
  }
}

void warn(String msg) {
  if (enableDebugLogging || logLevel == null || logLevel.toInteger() > 0) {
     log.warn msg
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

// vim: tabstop=2 shiftwidth=2 expandtab

