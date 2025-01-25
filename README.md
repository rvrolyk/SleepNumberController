# SleepNumberController

A driver and app to allow management of Sleep Number flexible bases in Hubitat.  It aims to allow users to control the each side of the bed as they would from the Sleep Number application, including limited control from Amazon Alexa or Google Assistant.

# Installation

## Using Hubitat Package Manager (recommended)

1. Install [Hubitat Package Manager](https://github.com/dcmeglio/hubitat-packagemanager)
1. Use the Package Manager to install Sleep Number Controller suite


## Manually (via import)

### Install Library
1. Under `For Developer`, open `Libaries code`
2. Click `+ Add Library`
3. Choose `Import` (from the three dot drop down on far right) and paste the URL: https://raw.githubusercontent.com/rvrolyk/SleepNumberController/refs/heads/master/SleepNumber_Library.groovy

### Install App

1. Under `For Developer`, open `Apps code` 
1. Click `New App` 
1. Choose `Import` (from the three dot drop down on far right) and paste the URL: https://raw.githubusercontent.com/rvrolyk/SleepNumberController/master/SleepNumberController_App.groovy

### Install Driver

1. Under `For Developer`, open `Drivers code`
1. Click `New Driver`
1. Choose `Import` (from the three dot drop down on far right) and paste the URL: https://raw.githubusercontent.com/rvrolyk/SleepNumberController/master/SleepNumberController_Driver.groovy

### Set up App

1. Go to `App`
1. Click `+ Add User App`
1. Choose `Sleep Number Controller` from the list
1. Proceed to set up

# Set Up

1. Enter your Sleep Number username and password and click `Create New Bed`
1. Follow the remaining pages to set up one or more devices for each side of the bed

You may select a refresh interval that varies based on time of data or a fixed one.  If you
are only using this for automation (not presence), I suggest a fixed one of 30 minutes or more since
automation will trigger a refresh anyway.  If you are using this for presence then I suggest a
variable refresh in order to avoid sending a lot of extra traffic to the SleepIQ servers.  For example,
you may choose to use every 30 minutes during the day but when you're normally in bed, reduce to every
minute or two.  The app will only change back to the day schedule when day start time and both sides
of the bed are away is true.  This will avoid reducing polling if you're still actively in bed (and
may want presence).  Instead of using fixed time periods to control the refresh interval, you may
also use modes by selecting *Use modes to control variable refresh interval* and then selecting
the modes you'd like to treat as night.

As of version 3, you may create bed devices as a parent/child device.  This is strongly recommended
if you intend to use devices with a voice control system or if you want to expose each device as a tile/button
in a dashboard.

## Logging

During normal operation, no changes are necessary to the logging in the app or devices.  If you're experiencing
problems, turning on debug logging in the app will cause it to emit more information about the requests and responses
being seen.

If you frequently have internet issues and would like to cut down on the amount of error logging about retries,
you may enter a value greater than 0 in *How often to allow error logs*.  This value should be an integer greater
than 0 to indicate how many minutes between error logs are permitted.  The system already tries to limit logging
to one error during each poll (indicating a retry) but you may use this control to show an error for every N periods.
For example, if your poll period is every minute you'd normally see an error each minute when the internet is down.
However, if this were set to 15 then you'd see an error every 15 minutes instead.

# Usage

I've attempted to allow multiple device types to be set up in order to expose multiple capabilities
via Amazon Alexa or Google Assistant.  If you only care about usage via Rule Machine, you can ignore all but the primary 
device type.  Note that not all commands are exposed at the level needed for external support; commands supported directly
are noted below and functionality is described.

## Supported Commands

* Set Bed Position: sets the position of the head or the foot of the bed.  The level is expected to be 0-100 (flat-fully raised).
This is supported via dimmer levels if the child device is *head* or *foot*.

* Set Foot Warming State: sets the foot warming duration and heat level.  If the child device is a *foot warmer* then the level may
be set as a dimmer level of 1, 2 or 3 (low, medium, high) and on/off turns the warmer on or off.  The duration and initial heat
level are preferences.

* Set Bed Preset: sets the bed to a preset level.  If the child device is a *head* or *foot*, then on will enable
the preset set as a preference (in this way, you can have 3 preferred preset levels via voice, one for primary device, one for head and one for foot) and off will set the bed to *flat*.

  Please note that a preset for Flextop beds (partial split) will result in changing *both* sides, not just the one selected.  This is
apparently by design (per SleepNumber).  If you want to change an individual head setting, I suggest using an automation vs. a preset.

* Set Bed Preset Timer: sets the bed to a preset level after an elapsed time.

* Stop Bed Position: stops the motion of the bed if it's currently moving.

* Enable Privacy Mode: turns on privacy mode so no data is reported to Sleep Number.

* Disable Privacy Mode: turns off privacy mode so sleep info is tracked again.

* Set Refresh Interval: changes the overall application refresh interval.  **NOTE**: This is a global setting.  It's exposed
at a device level so it can easily be changed via Rule Machine if needed.  An example use case would be to refresh quicker
when you're in bed (e.g, when presence is first detected) and then slower during the day.  I've found that a global refresh
of 1 minute (the fastest the app allows) doesn't seem to cause any problems for me but if you experience issues, this allows
you to have a slow refresh when you're not using the bed.  Also note that any command being sent will cause a refresh of **all states**.

* Set SleepNumber Favorite: sets the bed to the user's favorite SleepNumber value.

* Set Underbed Light State: if lighting is present, this will set the state of that light including timer and brightness level.

* Set Outlet State: if there are additional outlets (beyond the lighting) then this will set the state of those outlets (on/off).

* Set Responsive Air State: if this is toggled on (in the driver) then this will set the responsive air state to on or off

* Set Core Climate State: if the bed has core climate, this allows setting the temperature and timer.  There are preferences for the temperature level and timer if the child device is toggled on.

## Sleep Data

You can optionally enable sleep data collection (only works **in the presence device**) so that you can track
statistics from your last sleep session.  This enables collecting basic sleep data like how long you slept, how restless
you were, your heart rate, etc. and stores them as attributes. The data is refreshed when you leave the bed.

There are also two summary tiles that will be created that attempt to give a
single tile view of the useful data (similar to the SleepIQ app). 

Here's a sample tile:

![Image of sample tile](https://github.com/rvrolyk/SleepNumberController/raw/master/summary_tile.jpg)


### Styling

If you want to customize the look and feel of the tiles, you can use custom CSS.  Each tile has a unique
id to make selection easier.  It is composed of the tile type and the device label.  In each case, the 
device name is all lower case and any spaces are replaced with underscores.

* The Sleep IQ tile prefix is `sleepiq-summary-`
* The Session tile prefix is `session-summary-`

So if your device name is `My Bed` then you'd have two ids like:

1. sleepiq-summary-my_bed
1. session-summary-my_bed

To use those in css, for example to change the font colors, you would use:

```css
#sleepiq-summary-my_bed {
  color: blue
}

#session-summary-my_bed {
  color: green
}
```

In addition, there is a class applied to each table as well as one per tile type that lets you override all
tiles at once if that is desired.
The overall class name is `sleep-tiles` and the per type class names are `sleepiq-summary` and `session-summary`.
So if you wanted to set the font face of all tiles you could use the following
css:

```css
.sleep-tiles {
  font-family: arial
}
```

And if you just wanted to change the font fact of the sleep summary tile, you'd use:

```css
.session-summary {
  font-family: arial
}
```


# Donations

This work is fully Open Source, and available for use at no charge.

However, while not required, I do accept donations. If you would like to make an optional donation, I will be most grateful. You can make donations to me on PayPal at https://www.paypal.me/rvrolyk.

Note that acceptance of donation in no way guarentees support or updates.  I will do my best to keep this working (and use it myself) but things may change in the future.
