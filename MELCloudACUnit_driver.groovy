/**
 *  Mitsubishi Electric MELCloud AC Unit Child Driver
 *
 *  Copyright 2019 Simon Burke
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Change History:
 *
 *    Date        Who            What
 *    ----        ---            ----
 *    2019-12-06  Simon Burke    Original Creation
 *    2020-01-04  Simon Burke    Started adding Thermostat capability
 *    2020-02-09  Simon Burke    Thermostat capability and refreshing of state from MELCloud appear to be working
 *                               Starting work on splitting into Parent / Child Driver
 *
 * 
 */
import java.text.DecimalFormat;

metadata {
	definition (name: "MELCloud AC Unit", namespace: "simnet", author: "Simon Burke") {
        capability "Refresh"
        capability "Thermostat"
        
        attribute "authCode", "string"
        attribute "unitId", "number"
        
        //Thermostat capability attributes - showing default values from HE documentation
        /*
        attribute "coolingSetpoint",                "NUMBER"
        attribute "heatingSetpoint",                "NUMBER"
        attribute "schedule",                       "JSON_OBJECT"

        attribute "supportedThermostatFanModes",    "ENUM", ['on', 'circulate', 'auto']

        attribute "supportedThermostatModes",       "ENUM", ['auto', 'off', 'heat', 'emergency heat', 'cool']
        attribute "temperature",                    "NUMBER"
        attribute "thermostatFanMode",              "ENUM", ['on', 'circulate', 'auto']
        attribute "thermostatMode",                 "ENUM", ['auto', 'off', 'heat', 'emergency heat', 'cool']
        attribute "thermostatOperatingState",       "ENUM", ['heating', 'pending cool', 'pending heat', 'vent economizer', 'idle', 'cooling', 'fan only']
        attribute "thermostatSetpoint",             "NUMBER"
        */
        
        attribute "lastRunningMode",                "STRING"
        
        //MELCloud specific commands:
        command "on"
        
        //Thermostat capability commands
        /*
        command "auto"
        command "cool"
        command "emergencyHeat" //Unsupported in MELCloud
        command "fanAuto"
        command "fanCirculate"
        command "fanOn"
        command "heat"
        command "off"
        
        command "setCoolingSetpoint", [[name:"temperature*", type: "NUMBER", description: "Enter the Cooling Set Point" ] ]
                // temperature required (NUMBER) - Cooling setpoint in degrees
        command "setHeatingSetpoint", [[name:"temperature*", type: "NUMBER", description: "Enter the Heating Set Point" ] ]
                // temperature required (NUMBER) - Heating setpoint in degrees
        command "setSchedule", [[name:"JSON_OBJECT", type: "JSON_OBJECT", description: "Enter the JSON for the schedule" ] ]
            // JSON_OBJECT (JSON_OBJECT) - JSON_OBJECT
*/
        //Providing command with fan modes supported by MELCloud
        //command "setThermostatFanMode", [[name:"fanmode*", type: "ENUM", description: "Pick a Fan Mode", constraints: ["Low", "Mid", "High", "Auto"] ] ]
                // fanmode required (ENUM) - Fan mode to set
        
        //command "setThermostatMode", [[name:"thermostatmode*", type: "ENUM", description: "Pick a Thermostat Mode", constraints: ['Heat', 'Dry', 'Cool', 'Fan', 'Auto'] ] ]
                // thermostatmode required (ENUM) - Thermostat mode to set
        
	}

}


def refresh() {
  // Retrieve current state information from MEL Cloud Service   
  //getRooms()
  //initialize()
    parent.infoLog("refresh unit ${device.currentValue("unitId", true)}");
    unitCommand();
}

def initialize() {
    // Adjust default enumerations setup as part of the Hubitat Thermostat capability
    sendEvent(name: 'supportedThermostatFanModes', value: ['Low', 'Mid', 'High', 'Auto'])
    sendEvent(name: 'supportedThermostatModes', value: ['heat', 'dry', 'cool', 'fan', 'auto', 'off'])
    
    if ("${device.currentValue("coolingSetpoint")}" > 40)
    { sendEvent(name: "coolingSetpoint", value: "23.0")}
    
    if ("${device.currentValue("heatingSetpoint")}" > 40)
    { sendEvent(name: "heatingSetpoint", value: "23.0")}
    
}

def getFanModeMap() {
    [
        0:"auto",
        1:"low",
        2:"low-medium",
        3:"medium",
        5:"medium-high",
        6:"high"
    ]
}

def getModeMap() {
    [
        1:"heat",
        2:"dry",
        3:"cool",
        7:"fan",
        8:"auto"
    ]
}

def getRooms() {
    //retrieves current stat information for the ac unit
    
    def vUnitId = ""
    def vRoom = ""
    def vPower = ""
    def vMode = ""
    def vModeDesc = ""
    def vTemp = ""
    def vSetTemp = ""
    
    def bodyJson = "{ }"
    def headers = [:] 

    headers.put("Content-Type", "application/json")
    headers.put("Cookie", "auth=${parent.currentValue("authCode", true)}")
    headers.put("accept", "application/json, text/javascript, */*; q=0.01")
    def postParams = [
        uri: "${parent.getBaseURL()}rooms.aspx",
        headers: headers,
        contentType: "application/json",
        body : bodyJson
	]
    parent.debugLog("${bodyJson}, ${headers.Cookie}") 
	try {
        
        httpPost(postParams) { resp -> 
            parent.debugLog("GetRooms: Initial data returned from rooms.aspx: ${resp.data}") 
            resp?.data?.each { building -> // Each Building
                                building?.units?.each // Each AC Unit / Room
								{ acUnit -> 
									vRoom     = acUnit.room
									vUnitId   = acUnit.unitid
									vPower    = acUnit.power
									vMode     = acUnit.mode
									vTemp     = acUnit.temp
									vSetTemp  = acUnit.settemp
									parent.debugLog("updating ${vUnitId}")  
									def statusInfo = [:]
									statusInfo.unitid = acUnit.unitid
									statusInfo.power = acUnit.power
									statusInfo.setmode = acUnit.mode.toInteger()
									statusInfo.settemp = acUnit.settemp
									statusInfo.roomtemp = acUnit.temp
									log.debug("${vRoom}(${vUnitId}): ${statusInfo}")
									applyResponseStatus(statusInfo)
								} 
                			}
            }
    }   
	catch (Exception e) {
        parent.errorLog("GetRooms : Unable to query Mitsubishi Electric cloud: ${e}")
	}
}

def unitCommand(command) {
    // Re-usable method that submits a command to the MEL Cloud Service, based on the command text passed in
    // See https://github.com/NovaGL/diy-melview for more details on commands and this API more generally
 
    def bodyJson
    if (command != null) {
        bodyJson = "{ \"unitid\": \"${device.currentValue("unitId", true)}\", \"v\": 2, \"commands\": \"${command}\", \"lc\": 1 }"
    } else {
        bodyJson = "{ \"unitid\": \"${device.currentValue("unitId", true)}\", \"v\": 2 }"
    }
    def headers = [:] 

    headers.put("Content-Type", "application/json")
    headers.put("Cookie", "auth=${parent.currentValue("authCode", true)}")
    headers.put("accept", "application/json")
    def postParams = [
        uri: "${parent.getBaseURL()}unitcommand.aspx",
        headers: headers,
        contentType: "application/json",
        body : bodyJson
	]
    debugLog("will post: ${bodyJson}") //, ${headers.Cookie}")       
	try {
        def statusInfo = [:]
        httpPost(postParams) { resp ->
            debugLog("UnitCommand (${command}) got response - ${resp.data}")
            if (resp?.data?.error == "ok") {
                def unit = resp.data
                statusInfo.unitid = unit.id
                statusInfo.power = unit.power
                statusInfo.setmode = unit.setmode
                statusInfo.settemp = unit.settemp
                statusInfo.roomtemp = unit.roomtemp
                statusInfo.setfan = unit.setfan
                debugLog(statusInfo)
                applyResponseStatus(statusInfo)
            } else {
                parent.errorLog("UnitCommand (${command}): Error from Mitsubishi Electric cloud: ${resp?.data?.error?.fault}")
            }
        }
    }
	catch (Exception e) {
        parent.errorLog("UnitCommand (${command}): Unable to query Mitsubishi Electric cloud: ${e}")
	}
}

def applyResponseStatus(statusInfo) {
    debugLog("applyResponseStatus: incoming mode: ${statusInfo.setmode}")
    def vModeDesc = ""
    if (statusInfo.power == "q" || statusInfo.power == 0) {vModeDesc = "off"}
    else {
        vModeDesc = modeMap[statusInfo.setmode]
    }
    //log.debug(modeMap[8])
    debugLog("parsed mode: ${vModeDesc}")
    
    def tempscaleUnit = "°${location.temperatureScale}"
    def roomtempValue = convertTemperatureIfNeeded(statusInfo.roomtemp.toFloat(),"c",1)
    
    sendEvent(name: "temperature", value: roomtempValue, unit: tempscaleUnit)
    sendEvent(name: "thermostatMode", value: "${vModeDesc}")
    
    def operatingState
    if (vModeDesc == "off") {
        operatingState = "idle"
    } else if (vModeDesc == "heat") {
        operatingState = "heating"
    } else if (vModeDesc == "cool") {
        operatingState = "cooling"
    } else if (vModeDesc == "auto") {
        operatingState = "cooling"
    }
    
    log.debug("operatingState: ${operatingState}")
    if (operatingState != null) {
        sendEvent(name: "thermostatOperatingState", value: operatingState)
        if (operatingState != "idle") {
            sendEvent(name: "lastRunningMode", value: "${vModeDesc}")
        }
    }



    debugLog("checking settemp: ${statusInfo.settemp}")
    
    def setTemp = statusInfo.settemp
    def setTempValue = convertTemperatureIfNeeded(statusInfo.settemp.toFloat(),"c",1)
    sendEvent(name: "coolingSetpoint" , value: setTempValue, unit: tempscaleUnit)
    sendEvent(name: "heatingSetpoint" , value: setTempValue, unit: tempscaleUnit)
    sendEvent(name: "thermostatSetpoint" , value: setTempValue, unit: tempscaleUnit)
    
    debugLog("checking setfan: ${statusInfo.setfan}")
    if (statusInfo.setfan != null) {
        def fanMode = fanModeMap[statusInfo.setfan]
        sendEvent(name: "thermostatFanMode" , value: fanMode)
    }
}

//Unsupported commands from Thermostat capability
def emergencyHeat() { parent.debugLog("Emergency Heat Command not supported by MELCloud") }


//Fan Adjustments

//MELCloud Commands ('FS' 2 - LOW, 3 - MID, 5 - HIGH, 0 - AUTO)

def fanAuto() {
    unitCommand("FS0")
}

def fanCirculate() {
    unitCommand("FS5")
}

//fanOn - see modes section

//Scheduling Adjustments - Unsupported at present
def setSchedule(JSON_OBJECT) {parent.debugLog("setSchedule not currently supported by MELCloud")}



//Temperature Adjustments



def setCoolingSetpoint(temperature) {
    def temperatureValue = convertTemperatureIfNeeded(temperature.toFloat(),"c",1)
    sendEvent(name: "coolingSetpoint", value : temperatureValue, unit: "°${location.temperatureScale}")
    parent.infoLog("${device.label} - Cooling Set Point adjusted to ${temperature}")
    def setTemp = setTemperatureCommand(temperature)
}

def setHeatingSetpoint(temperature) {
    def temperatureValue = convertTemperatureIfNeeded(temperature.toFloat(),"c",1)
    sendEvent(name: "heatingSetpoint", value : temperatureValue, unit: "°${location.temperatureScale}")
    parent.infoLog("${device.label} - Heating Set Point adjusted to ${temperature}")
    def setTemp = setTemperatureCommand(temperature)
}

def setThermostatFanMode(fanmode) {
    
    debugLog("setThermostatFanMode: Fan Mode set to ${fanmode}")
    sendEvent(name: "thermostatFanMode", value : fanmode)
    parent.infoLog("${device.label} - Fan Mode set to ${fanmode}")
}


def setThermostatMode(thermostatmodeX) {
    
    if (thermostatmodeX != device.currentValue("thermostatMode")) {
        
        sendEvent(name: "thermostatMode", value : thermostatmodeX)
        sendEvent(name: "thermostatOperatingState", value : thermostatmodeX)
        parent.infoLog("${device.label} - Thermostat Mode Set to ${thermostatmodeX}")
        
        if (thermostatmodeX == 'dry') { dry() }
        if (thermostatmodeX == 'cool') { cool() }
        if (thermostatmodeX == 'heat') { heat() }
        if (thermostatmodeX == 'auto') { auto() }
        if (thermostatmodeX == 'fan') { fanOn() }
        if (thermostatmodeX == 'off') { off() }
    }
}


def setTemperature(temperature) {
    debugLog("setTemperature: ${temperature}")
    def tempSet = setTemperatureCommand(temperature)
    
    sendEvent(name: "thermostatSetpoint", value: tempSet, unit: "°${location.temperatureScale}")
}

def setTemperatureCommand(temperature) {
    debugLog("setTemperatureCommand: '${temperature}'")   
    
    if (temperature == null) {
        return;
    }
    DecimalFormat tf = new DecimalFormat ("##.0");
    def tempParam = tf.format(temperature);

    debugLog("setTemperatureCommand: will request ${tempParam}")        
    
    unitCommand("TS${tempParam}")
     
    return tempParam
}


//Power and State Adjustments

def on() {
    
    unitCommand("PW1")    
}

def off() {
    
    unitCommand("PW0")
}

//Modes:

//"MD1" - HEAT
//"MD2" - DRY
//"MD3" - Cooling
//"MD7" - FAN
//"MD8" - Auto

def auto() {
    on()
    unitCommand("MD8")
}

def fanOn() {
    on()
    unitCommand("MD7")
}

def cool() {
    on()
    unitCommand("MD3")
    if (device.currentValue("coolingSetpoint") != null) {
        setTemperature(device.currentValue("coolingSetpoint"))
    } else {
        // rely on response parsing setting status of the setpoints so user will see what it's currently set to
    }
}

def dry() {
    on()
    unitCommand("MD2")    
}

def heat() {
    on()
    unitCommand("MD1")
    if (device.currentValue("heatingSetpoint") != null) {
        setTemperature(device.currentValue("heatingSetpoint"))
    } else {
        // rely on response parsing setting status of the setpoints so user will see what it's currently set to
    }
}

def debugLog(debugMessage) {
	//if (parent.DebugLogging == true)
    log.debug(debugMessage)	
}

def errorLog(errorMessage) {
    //if (parent.ErrorLogging == true)
    log.error(errorMessage)
}

def infoLog(infoMessage) {
    //if(parent.InfoLogging == true)
    log.info(infoMessage)  
}
 


