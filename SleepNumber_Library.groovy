import groovy.transform.Field

/**
 * Library to collect common methods and constants to use between app and driver.
 */ 

library(
   author: 'rvrolyk',
   name: 'SleepNumberLibraryBeta',
   namespace: 'rvrolyk',
   description: 'Library for Sleep Number'
)

/*------------------ Shared constants ------------------*/

// If true, change library imports and the name of this library
@Field static final Boolean IS_BETA = true
@Field static final String appVersion = '4.0.1'  // public version; sync w/ manifest
@Field static final String NAMESPACE = 'rvrolyk'
@Field static final String DRIVER_PREFIX = 'Sleep Number Bed'
@Field static final String APP_PREFIX = 'Sleep Number Controller'
@Field static final String BETA_SUFFIX = ' Beta'
static String getAPP_NAME() { APP_PREFIX + (IS_BETA ? BETA_SUFFIX : '') }
static String getDRIVER_NAME() { DRIVER_PREFIX + (IS_BETA ? BETA_SUFFIX : '') }

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

/*------------------ Various constants ------------------*/

@Field static final String sBLK = ''
@Field static final String sSPACE = ' '
@Field static final Integer iZ = 0
@Field static final Integer i1 = 1
@Field static final Integer i2 = 2
@Field static final Integer i3 = 3
@Field static final Integer i4 = 4
@Field static final Integer i20 = 20
@Field static final String sNL = (String)null

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

/*------------------ SleepNumber constants ------------------*/

@Field static final String sSTON = 'On'
@Field static final String sSTOFF = 'Off'
@Field static final String sRIGHT = 'Right'
@Field static final String sLEFT = 'Left'

@Field static final String sHEAD = 'head'
@Field static final String sFOOT = 'foot'
@Field static final String sFOOTWMR = 'footwarmer'
@Field static final String sOUTLET = 'outlet'
@Field static final String sUNDERBEDLIGHT = 'underbedlight'

@Field static final ArrayList<String> SIDES = ['Right', 'Left']
@Field static final Map<String, String> ACTUATOR_TYPES = ['head': 'H', 'foot': 'F']
@Field static final Map<String, String> VALID_ACTUATORS = ['H':'Head', 'F':'Foot']
@Field static final Map<String, Integer> HEAT_TIMES = ['30m': 30, '1h': 60, '2h': 120, '3h': 180, '4h': 240, '5h': 300, '6h': 360]
@Field static final ArrayList<Integer> VALID_HEAT_TIMES = [30, 60, 120, 180, 240, 300, 360]
@Field static final Map<String, Integer> HEAT_TEMPS = ['Off': 0, 'Low': 31, 'Medium': 57, 'High': 72]
@Field static final Map<Integer, String> VALID_HEAT_TEMPS = [0: 'off', 31: 'low', 57: 'medium', 72: 'high']
@Field static final Map<String, Integer> PRESET_TIMES = ['Off': 0, '15m': 15, '30m': 30, '45m': 45, '1h': 60, '2h': 120, '3h': 180]
@Field static final ArrayList<Integer> VALID_PRESET_TIMES =  [0, 15, 30, 45, 60, 120, 180]
@Field static final Map<String, Integer> PRESET_NAMES = ['Favorite': 1, 'Flat': 4, 'ZeroG': 5, 'Snore': 6, 'WatchTV': 3, 'Read': 2]
@Field static final Map<Integer, String> VALID_PRESETS = [1: 'favorite', 2: 'read', 3: 'watch_tv', 4: 'flat', 5: 'zero_g', 6: 'snore']
@Field static final ArrayList<String> UNDERBED_LIGHT_STATES = ['Auto', 'On', 'Off']
@Field static final Map<String, Integer> UNDERBED_LIGHT_TIMES = ['Forever': 0, '15m': 15, '30m': 30, '45m': 45, '1h': 60, '2h': 120, '3h': 180]
@Field static final Map<Integer, String> VALID_LIGHT_TIMES = [15: '15m', 30: '30m', 45: '45m', 60: '1h', 120: '2h', 180: '3h']
@Field static final Map<String, Integer> UNDERBED_LIGHT_BRIGHTNESS = ['Off': 0, 'Low': 1, 'Medium': 30, 'High': 100]
@Field static final Map<Integer, String> VALID_LIGHT_BRIGHTNESS = [0: 'off', 1: 'low', 30: 'medium', 100: 'high']
@Field static final ArrayList<String> OUTLET_STATES = ['On', 'Off']
@Field static final ArrayList<Integer> VALID_SPEEDS = [0, 1, 2, 3]
@Field static final ArrayList<String> CORE_CLIMATE_TEMPS =  ['OFF', 'HEATING_PUSH_LOW', 'HEATING_PUSH_MED', 'HEATING_PUSH_HIGH', 'COOLING_PULL_LOW', 'COOLING_PULL_MED', 'COOLING_PULL_HIGH']
@Field static final Integer MAX_CORE_CLIMATE_TIME = 600


/*
 Fuzion TODOs

        Massage
        ---------------------
        setFoundationMassage

        don't have any examples of this working. Is it even supported?


*/

// vim: tabstop=2 shiftwidth=2 expandtab:w
