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
 *  of those but leverages prior work they"ve done for the API calls and bed management.
 *    https://github.com/natecj/SmartThings/blob/master/smartapps/natecj/sleepiq-manager.src/sleepiq-manager.groovy
 *    https://github.com/ClassicTim1/SleepNumberManager/blob/master/FlexBase/SmartApp.groovy
 */
import groovy.transform.Field

@Field final String DRIVER_NAME = "Sleep Number Bed"
@Field final String NAMESPACE = "rvrolyk"
@Field final String API_HOST = "prod-api.sleepiq.sleepnumber.com"
@Field final String API_URL = "https://" + API_HOST
@Field final String USER_AGENT = '''\
Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36'''

@Field final ArrayList VALID_ACTUATORS = ["H", "F"]
@Field final ArrayList VALID_WARMING_TIMES = [30, 60, 120, 180, 240, 300, 360]
@Field final ArrayList VALID_WARMING_TEMPS = [0, 31, 57, 72]
@Field final ArrayList VALID_PRESET_TIMES = [0, 15, 30, 45, 60, 120, 180]
@Field final ArrayList VALID_PRESETS = [1, 2, 3, 4, 5, 6]

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
}

def homePage() {
  def devices = getBedDevices()

  dynamicPage(name: "homePage") {
    section("<b>Settings</b>") {
      input name: "login", type: "text", title: "sleepnumber.com email",
          description: "Email address you use with Sleep Number", submitOnChange: true
      input name: "password", type: "password", title: "sleepnumber.com password",
          description: "Password you use with Sleep Number", submitOnChange: true
      input name: "refreshInterval", type: "number", title: "Refresh Interval (minutes)",
          description: "How often to refresh bed state", defaultValue: 1
    }

    section("<b>Bed Management</b>") {
      if (!settings.login || !settings.password) {
        paragraph "Add login and password to find beds"
      } else {
        if (devices.size() > 0) {
          paragraph "Current beds"
          devices.each { device ->
            paragraph  "${device.getState().bedId} / ${device.label} / ${device.getState().side} / ${device.getState().type}"
          }
          paragraph "<br>Note: <i>To remove a device remove it from the Devices list</i>"
        }
        // Only show bed search if user entered creds
        if (settings.login && settings.password) {
          href "findBedPage", title: "Create New Bed", description: "Search for beds"
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
      label title: "Assign an app name", required: false
      mode title: "Set for specific mode(s)", required: false
      input "logEnable", "bool", title: "Enable debug logging?", defaultValue: false, required: true
    }
  }
}

def installed() {
  initialize()
}

def updated() {
  unsubscribe()
  unschedule()
  initialize()
}

def initialize() {
  refreshChildDevices()
  schedule("0 /${settings.refreshInterval} * * * ?", "refreshChildDevices")
}

/**
 * Gets all bed child devices even if they're in a virtual container.
 * Will not return the virtual container(s)
 */
def getBedDevices() {
  children = []
  // If any child is a virtual container, iterate that too
  getChildDevices().each() { child ->
    if (child.hasAttribute("containerSize")) {
      children.addAll(child.childList())
    } else {
      children.add(child)
    }
  }
  return children
}

def refreshChildDevices() {
  getBedData()
}

def refreshChildDevices(ignored, ignoredDevId) {
  refreshChildDevices()
}

/**
 * Sets the refresh interval or resets to the app setting value if
 * 0 is given.
 * Can be used when refresh interval is long (say 15 or 30 minutes) during the day
 * but quicker, say 1 minute, is desired when presence is first detected or it's
 * a particular time of day.
 */
def setRefreshInterval(val, devId) {
  debug "setRefreshInterval(${val})"
  if (val && val > 0) {
    schedule("0 /${val} * * * ?", "refreshChildDevices")
  } else {
    debug "Resetting interval to ${settings.refreshInterval}"
    schedule("0 /${settings.refreshInterval} * * * ?", "refreshChildDevices")
  }
}

def findBedPage() {
  def responseData = getBedData()
  dynamicPage(name: "findBedPage") {
    if (responseData.beds.size() > 0) {
      responseData.beds.each { bed ->
        section("Bed: ${bed.bedId}") {
          def leftPresence = bed.leftSide.isInBed
          def rightPresence = bed.rightSide.isInBed
          href "selectBedPage", title: "Left Side", description: presenceText(leftPresence),
              params: [bedId: bed.bedId, side: "Left", presence: leftStatus]
          href "selectBedPage", title: "Right Side", description: presenceText(rightPresence),
              params: [bedId: bed.bedId, side: "Right", presence: rightStatus]
        }
      }
    } else {
      section {
        paragraph "No Beds Found"
      }
    }
  }
}

def presenceText(presence) {
  return presence ? "Present" : "Not Present"
}

def selectBedPage(params) {
  settings.newDeviceName = null

  dynamicPage(name: "selectBedPage") {
    if (!params?.bedId) {
      section {
        href "homePage", title: "Home", description: null
      }
      return
    }
    section {
      paragraph """<b>Instructions</b>
Enter a name, then choose whether or not to use a virtual container for the devices and then choose the types of devices to create.
Note all devices are the same on Hubitat, the only difference is how they behave to dim and on/off commands.  This is so that they may be used with external assistants such as Google Assistant or Amazon Alexa.  If you don't care about such use cases (and only want RM control or just presence), you can just use the presence type.
See <a href="https://community.hubitat.com/t/release-virtual-container-driver/4440" target=_blank>this post</a> for virtual container.
"""
        paragraph """<b>Device information</b>
Bed ID: ${params.bedId}
Side: ${params.side}
Presence: ${presenceText(params.presence)}
""" 
    }
    section {
      input "newDeviceName", "text", title: "Device Name",
          description: "What do you want to call the devices?", submitOnChange: true,
          required: true
      input "useContainer", "bool", title: "Use virtual container?", defaultValue: true,
         submitOnChange: true
      paragraph "A presence type device exposes on/off as switching to a preset level (on) and flat (off).  Dimming will change the Sleep Number."
      input "createPresence", "bool",
          title: "Create presence device for ${params.side.toLowerCase()} side?",
          defaultValue: true, submitOnChange: true
      paragraph "A head type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the head position (0 is flat, 100 is fully raised)."
      input "createHeadControl", "bool",
         title: "Create device to control the head of the ${params.side.toLowerCase()} side?",
         defaultValue: true, submitOnChange: true
      paragraph "A foot type device exposes on/off as switching to a preset level (on) and  flat (off).  Dimming will change the foot position (0 is flat, 100 is fully raised)."
      input "createFootControl", "bool",
         title: "Create device to control the foot of the ${params.side.toLowerCase()} side?",
         defaultValue: true, submitOnChange: true
      paragraph "A foot type device exposes on/off as switching the foot warming on or off.  Dimming will change the heat levels (1: low, 2: medium, 3: high)."
      input "createFootWarmer", "bool",
         title: "Create device to control the foot warmer of the ${params.side.toLowerCase()} side?",
         defaultValue: true, submitOnChange: true
    }
    section {
      String msg = "Will create the following devices"
      def containerName = ""
      def types = []
      if (settings.useContainer) {
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
      msg += "</ol>"
      paragraph msg
      href "createBedPage", title: "Create Devices", description: null,
      params: [
        presence: params.present,
        bedId: params.bedId,
        side: params.side,
        useContainer: settings.useContainer,
        containerName: containerName,
        types: types
      ]
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
    default:
      return "${name} Unknown"
  }
}

def createBedPage(params) {
  def container = null
  if (params.useContainer) {
    container = createContainer(params.bedId, params.containerName, params.side)
  }
  def existingDevices = getBedDevices()
  def devices = []
  params.types.each { type ->
    def deviceId = "sleepnumber.${params.bedId}.${params.side}.${type.replaceAll(' ', '_')}"
    if (existingDevices.find{ it.data.vcId == deviceId }) {
      log.info "Not creating device ${deviceId}, it already exists"
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
  settings.newDeviceName = null
  dynamicPage(name: "selectDevicePage") {
    section {
      paragraph("Created new devices" + (params.useContainer ? " in container ${params.containerName}: " : ":"))
      def info = "<ol>"
      devices.each { device ->
        info += "<li>"
        info += "Label: ${device.label}"
        info += "<br>Bed ID: ${device.getState().bedId}"
        info += "<br>Side: ${device.getState().side}"
        info += "<br>Type: ${device.getState().type}"
        info += "</li>"
      }
      info += "</ol>"
      paragraph info
    }
    section {
      href "findBedPage", title: "Back to Bed List", description: null
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
def processBedData(responseData) {
  if (!responseData || responseData.size() == 0) {
    debug "Empty response data"
    return
  }
  // cache for foundation status per bed id so we don't have to run the api call N times
  def foundationStatus = [:]
  def footwarmingStatus = [:]
  for (def device : getBedDevices()) {
    for (def bed : responseData.beds) {
      if (device.getState().bedId == bed.bedId) {
        if (!foundationStatus.get(bed.bedId)) {
          foundationStatus[bed.bedId] = getFoundationStatus(device.getState().bedId, device.getState().side)
        }
        if (!footwarmingStatus.get(bed.bedId)) {
          footwarmingStatus[bed.bedId] = getFootWarmingStatus(device.getState().bedId)
        }

        def bedSide = device.getState().side == "Right" ? bed.rightSide : bed.leftSide
        device.setPresence(bedSide.isInBed)
        def statusMap = [
          sleepNumber: bedSide.sleepNumber,
        ]
        // Check for valid foundation status and footwarming status data before trying to use it
        // as it's possible the HTTP calls failed.
        if (foundationStatus.get(bed.bedId)) {
	  // Positions are in hex so convert to a decimal
          def headPosition = convertHexToNumber(foundationStatus.get(bed.bedId)."fs${device.getState().side}HeadPosition")
          def footPosition = convertHexToNumber(foundationStatus.get(bed.bedId)."fs${device.getState().side}FootPosition")
          def bedPreset = foundationStatus.get(bed.bedId)."fsCurrentPositionPreset${device.getState().side}"
          // There's also a MSB timer but not sure when that gets set.  Least significant bit seems used for all valid times.
          def positionTimer = convertHexToNumber(foundationStatus.get(bed.bedId)."fs${device.getState().side}PositionTimerLSB")
          statusMap << [
            headPosition: headPosition,
            footPosition:  footPosition,
            positionPreset: bedPreset,
            positionPresetTimer: foundationStatus.get(bed.bedId)."fsTimerPositionPreset${device.getState().side}",
            positionTimer: positionTimer
          ]
        } else {
          log.info "Not updating foundation state, no data"
        }
        if (footwarmingStatus.get(bed.bedId)) {
          statusMap << [
            footWarmingTemp: footwarmingStatus.get(bed.bedId)."footWarmingStatus${device.getState().side}",
            footWarmingTimer: footwarmingStatus.get(bed.bedId)."footWarmingTimer${device.getState().side}",
          ]
        } else {
          log.info "Not updating footwarming state, no data"
        }

        device.setStatus(statusMap)
        break
      }
    }
  }
}

def convertHexToNumber(value) {
  try {
    return Integer.parseInt(value, 16)
  } catch (Exception e) {
    log.err "Failed to convert non-numeric value ${value}: ${e}"
    return value
  }
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

/**
 * Params must be a Map containing keys actuator and position.
 * The side is derived from the specified device.
 */
def setFoundationAdjustment(Map params, devId) {
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
  def body = [
    speed: 0,
    actuator: params.actuator,
    side: device.getState().side[0],
    position: params.position
  ]
  httpRequest("/rest/bed/${device.getState().bedId}/foundation/adjustment/micro", this.&put, body)
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // Since we only really care about refreshing the device at the end (whereas the app shows the bed move), we
  // just wait until it should be done and then refresh once.  
  // We add an extra second just to increase the odds that it's actually done.
  def waitTime = params.actuator == "H" ? 36 : 19
  runIn(waitTime, "refreshChildDevices") 
}

/**
 * Params must be a Map containing keys temp and timer.
 * The side is derived from the specified device.
 */
def setFootWarmingState(Map params, devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  if (!params?.temp == null || params?.timer == null) {
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
  def body = [
    "footWarmingTemp${device.getState().side}": params.temp,
    "footWarmingTimer${device.getState().side}": params.timer
  ]
  httpRequest("/rest/bed/${device.getState().bedId}/foundation/footwarming", this.&put, body)
  // Shouldn't take too long for the bed to reflect the new state, wait 10s just to be safe
  runIn(10, "refreshChildDevices")
}

/**
 * Params must be a map containing keys preset and timer.
 * The side is derived from the specified device.
 */
def setFoundationTimer(Map params, devId) {
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
  def body = [
    side: device.getState().side[0],
    positionPreset: params.preset,
    positionTimer: params.timer
  ]
  httpRequest("/rest/bed/${device.getState().bedId}/foundation/adjustment", this.&put, body)
  // Shouldn't take too long for the bed to reflect the new state, wait 10s just to be safe
  runIn(10, "refreshChildDevices")
}

def setFoundationPreset(preset, devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  if (!VALID_PRESETS.contains(preset)) {
    log.error "Invalid preset ${preset}, valid values are ${VALID_PRESETS}"
    return
  }
  def body = [
    speed: 0,
    preset : preset,
    side: device.getState().side[0],
  ]
  httpRequest("/rest/bed/${device.getState().bedId}/foundation/preset", this.&put, body)
  // It takes ~35 seconds for a FlexFit3 head to go from 0-100 (or back) and about 18 seconds for the foot.
  // I didn't run a time per preset so just wait 35 seconds which is the longest this should take.
  runIn(35, "refreshChildDevices") 
}

def stopFoundationMovement(ignored, devId) {
  def device = getBedDevices().find { devId == it.deviceNetworkId }
  if (!device) {
    log.error "Bed device with id ${devId} is not a valid child"
    return
  }
  def body = [
    massageMotion: 0,
    headMotion: 1,
    footMotion: 1,
    side: device.getState().side[0],
  ]
  httpRequest("/rest/bed/${device.getState().bedId}/foundation/motion", this.&put, body)
  runIn(10, "refreshChildDevices")
}

def login() {
  debug "Logging in"
  state.session = null
  try {
    def params = [
      uri: API_URL + "/rest/login",
      requestContentType: "application/json",
      contentType: "application/json",	
      headers: [
        "Host": API_HOST,
        "User-Agent": USER_AGENT,
        "DNT": "1",
      ],
      body: "{'login':${settings.login}, 'password':${settings.password}}="
    ]
    httpPut(params) { response ->
      if (response.status == 200) {
        debug "login Success: (${response.status}) ${response.data}"
        state.session = [:]
        state.session.key = response.data.key
        state.session.cookies = ""
        response.getHeaders("Set-Cookie").each {
          state.session.cookies = state.session.cookies + it.value.split(";")[0] + ";"
        }
      } else {
        debug "login Failure: (${response.status}) ${response.data}"
        state.session = null
      }
    }
  } catch (Exception e) {
    log.error "login Error: ${e}"
    state.session = null
  }
}

def httpRequest(path, method = this.&get, body = null, alreadyTriedRequest = false) {
  if ((!state.session || !state.session?.key) && alreadyTriedRequest) {
    log.error "Already attempted login, giving up"
    return [:]
  }
  def payload = body ? new groovy.json.JsonBuilder(body).toString() : null
  def statusParams = [
    uri: API_URL,
    path: path,
    requestContentType: "application/json",
    contentType: "application/json",
    headers: [
      "Host": API_HOST,
      "User-Agent": USER_AGENT,
      "Cookie": state.session?.cookies,
      "DNT": "1",
    ],
    query: [_k: state.session?.key],
    body: payload,
  ]
  if (payload) {
    debug "Sending payload to ${path}: ${payload}"
  }
  try {
    return method(statusParams) { response -> 
      if (response.status == 200) {
        return response.data
      } else {
        log.error "Failed with request for ${path} with payload/ ${payload}: (${response.status}) ${response.data}"
        state.session = null
        return [:]
      }
    }
  } catch (Exception e) {
    if (!e.toString().contains("Unauthorized")) {
      // Only log the error if it's not unauthorized since we'll retry that
      // or log failure elsewhere anyway
      log.error "Error ${e} in request: ${statusParams}"
    }
    state.session = null
    state.session?.key = null
    if (!alreadyTriedRequest) {
      login()
      return httpRequest(path, method, body, true)
    } else {
      return [:]
    }
  }
}

def debug(msg) {
  if (logEnable) {
    log.debug msg
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
