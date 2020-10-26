# SleepNumberController

A driver and app to allow management of Sleep Number flexible bases in Hubitat.  It aims to allow users to control the each side of the bed as they would from the Sleep Number application, including limited control from Amazon Alexa or Google Assistant.

# Installation

## Using Hubitat Package Manager (recommended)

1. Install [Hubitat Package Manager](https://github.com/dcmeglio/hubitat-packagemanager)
1. Use the Package Manager to install Sleep Number Controller suite


## Manually (via import)

### Install App

1. Go to `<> Apps Code` menu
1. Click `+ New App` 
1. Hit `Import` at the top of the page and paste the URL: https://raw.githubusercontent.com/rvrolyk/SleepNumberController/master/SleepNumberController_App.groovy

### Install Driver

1. Go to `<> Drivers Code` menu
1. Click `+ New Driver`
1. Hit `Import` at the top of the page and paste the URL: https://raw.githubusercontent.com/rvrolyk/SleepNumberController/master/SleepNumberController_Driver.groovy

### Set up App

1. Go to `App`
1. Click `+ Add User App`
1. Choose `Sleep Number Controller` from the list
1. Proceed to set up

# Set Up

1. Enter your Sleep Number username and password and click `Create New Bed`
1. Follow the remaining pages to set up one or more devices for each side of the bed

# Usage

I've attempted to allow multiple device types to be set up (using the same driver) in order to expose multiple capabilities
via Amazon Alexa or Google Assistant.  If you only care about usage via Rule Machine, you can ignore all but the primary *presence*
device type as every device exposes the same commands.  Note that not all commands are exposed at the level needed for external
support; commands supported directly are noted below and functionality is described.

## Supported Commands

* Set Bed Position*: sets the position of the head or the foot of the bed.  The level is expected to be 0-100 (flat-fully raised).
This is supported via dimmer levels if the device type is *head* or *foot*.

* Set Foot Warming State*: sets the foot warming duration and heat level.  If the device type is *foot warmer* then the level may
be set as a dimmer level of 1, 2 or 3 (low, medium, high) and on/off turns the warmer on or off.  The duration and initial heat
level are preferences.

* Set Bed Preset*: sets the bed to a preset level.  If the device type is *presence*, *head* or *foot*, then on will set enable
the preset set as a preference (in this way, you can have 3 preferred preset levels via voice) and off will set the bed to *flat*.

  Please note that a preset for Flextop beds (partial split) will result in changing *both* sides, not just the one selected.  This is
apparently by design (per SleepNumber).  If you want to change an individual head setting, I suggest using automation vs. a preset.

* Set Bed Preset Timer: sets the bed to a preset level after an elapsed time.

* Stop Bed Position: stops the motion of the bed if it's currently moving.

* Enable Privacy Mode: turns on privacy mode so no data is reported to Sleep Number.

* Disable Privacy Mode: turns off privacy mode so sleep info is tracked again.

* Set Refresh Interval: changes the overall application refresh interval.  **NOTE**: This is a global setting.  It's exposed
at a device level so it can easily be changed via Rule Machine if needed.  An example use case would be to refresh quicker
when you're in bed (e.g, when presence is first detected) and then slower during the day.  I've found that a global refresh
of 1 minute (the fastest the app allows) doesn't seem to cause any problems for me but if you experience issues, this allows
you to have a slow refresh when you're not using the bed.  Also note that any command being sent will cause a refresh of all
states.

* Set SleepNumber Favorite: sets the bed to the user's favorite SleepNumber value.

* Set Underbed Light State: sets the bed's underbed lighting state (if present).  If there are two lights present (as some beds
have), this will set the side relative to the device side.

* Set Outlet State: sets the bed's outlet state (on/off) relative to the device side.


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
