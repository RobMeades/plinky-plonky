# Introduction
For Christmas 2025 Santa brought Hazel a music box mechanism, henceforth referred to as a "plinky-plonky".  Since it was difficult to turn at speed, I did a quick [motorised version](https://www.youtube.com/watch?v=XHdWuA18UNk) with some bits I was able to get hold of quickly: a motor controller with a speed knob on it and a 5&nbsp;V motor which purported to be silent.  The motor was not silent at all.

Since then I have made a [working Camberwick Green musical box](https://www.meades.org/misc/musical_box/musical_box.html) using one of these mechanisms, employing several varieties of stepper motor and a TMC2209 stepper motor controller.  Hence this repository contains the updated version of the motorised plink-plonky, employing a much quieter stepper motor, TMC2209, ESP32 and using Bluetooth for control.

In order to make this motorised plinky-plonky you will need to be able to:

- 3D print in FDM,
- solder,
- wire-up a small amount of electronics,
- write, or at least compile, C code (based on [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/get-started/index.html) for the ESP32),
- write a simple Android application to drive the Android Bluetooth Classic Serial Port Protocol (SPP) API.

You will need the following tools:

- an FDM 3D printer,
- a soldering iron,
- a wire stripper, 
- a wire snippers,
- a crimp tool for 0.1&nbsp;inch pitch connectors,
- a set of small allen keys, including 1.5&nbsp;mm and 2.5&nbsp;mm,
- an M3 tap and tap holder,
- a hair drier or other hot air blower capable of shrinking heat-shrink,
- a cross-head screwdriver,
- a flat-head screwdriver,
- an Android phone.

# 3D Printed Parts
The 3D printed parts, along with exported `.stl`, are available in the [blender](blender) folder; see there for printing instructions.

# Parts List
The following parts are required:

- the 3D printed parts from the [blender](blender) folder (which will require filament of your choice),
- a 15&nbsp;note plinky-plonky mechanism with fixing screws: the 3D printed parts are designed for one of [these](https://www.ebay.co.uk/itm/397247369927),
- an ESP32 board with headers soldered-in on the component side: the 3D printed parts are designed for one of [these](https://www.aliexpress.com/item/1005005780805655.html), you could of course modify them to take any small ESP32 board,
- a BigTreeTech TMC2209 board, i.e. one of [these](https://www.aliexpress.com/item/33028050145.html), any "sticky out" pins on the reverse lopped off and heatsink fitted,
- a 12/24&nbsp;V to 5&nbsp;V DC regulator: the 3D printed parts are designed for one of [these](https://www.aliexpress.com/item/1005010692851197.html), you could of course modify them to take any small regulator board; the current requirements for the 5&nbsp;V supply are negligible,
- a NEMA17 stepper motor: the 3D printed parts are designed for one of [these](https://www.amazon.co.uk/dp/B0D7PBB17L), frame size 42&nbsp;mm&nbsp;x&nbsp;42&nbsp;mm with M3 threaded mounting holes, frame 20&nbsp;mm deep, shaft 5&nbsp;mm diameter and 20&nbsp;mm long, with flat,
- a centre-positive plug-top power supply capable of delivering up to 2&nbsp;Amps at between 12 and 24&nbsp;V DC, terminating in a 2.1&nbsp;mm&nbsp;x&nbsp;5.5&nbsp;mm connector,
- an M3 hex-head grub screw (any length, the longer the better),
- 2 off M3&nbsp;x&nbsp;5&nbsp;mm hex head bolts,
- 3 off M3&nbsp;x&nbsp;10&nbsp;mm hex head bolts,
- 4 off M3&nbsp;x&nbsp;5&nbsp;mm pan head bolts,
- a 2.1&nbsp;mm&nbsp;x&nbsp;5.5&nbsp;mm panel-mount power socket,
- 1 off 1&nbsp;kOhm resistor,
- a small amount of 7-strand wire in  a few shades of red if you can (12&nbsp;V, 5&nbsp;V and 3.3&nbsp;V), black and a signal colour of your choice (e.g. yellow or white or blue),
- some 3&nbsp;mm diameter heat-shrink,
- 0.1&nbsp;inch pitch housings (1-way, 2-way and 4-way) plus female pins to go with,
- a small number of small sticky pads,
- optional: 1 off 4&nbsp;x&nbsp;8&nbsp;x&nbsp;3&nbsp;mm (inside diameter x outside diameter x thickness) bearing: I found that the sheath on the handle of the plinky-plonky mechanism rattled irritatingly so I took it off and slotted the handle into one of these bearings in the 3D printed wheel instead.

# Assembly Instructions
Assembly goes as follows (see also pictures below):

- if you intend to use the bearing to hold the handle of the plinky-plonky mechanism in the wheel, remove the sheath from the handle of the plinky-plonky mechanism by delving about in the end of it with a screwdriver to release the spring clip and hence the sheath,
- fix the plinky-plonky mechanism into place on top of the 3D printed body using the three fixing screws that were supplied with the mechanism, handle facing the stepper motor mount,
- fix the voltage regulator into place in the 3D printed body using the four M3&nbsp;x&nbsp;5&nbsp;mm pan head bolts, input end nearest the round hole in the side of the 3D printed body,
- I had trouble with resonance and, specifically the ESP32 and TMC2209 boards rattling very slightly, so I stuck a few pieces of sticky pad underneath where they would go to dampen things down a bit,
- clip the ESP32 and TMC2209 boards into their mounting positions in the 3D printed body, the TMC2209 board oriented with its red connector nearest the voltage regulator board, clipping the right hand side of both boards in first (when viewed with the lettering the right way up) in order to minimise the chances of damage; the USB-C connector of the ESP32 board needs to be lined up square with the rectangular hole in the 3D printed body,
- trim the stepper motor wires such that they will reach through the channel at the bottom left of the stepper motor mounting bracket and to the TMC22209 board mounting position in the 3D printed box (probably about 150&nbsp;mm) then terminate the wires in a 4-way 0.1&nbsp;inch pitch connector, order as in the schematic below, checking that the stepper motor you are using follows the same colour convention and adjusting as necessary,
- run an M3 tap through the grub screw hole in the 3D printed wheel,
- if you are using the 3D printed wheel that takes the 4&nbsp;x&nbsp;8&nbsp;x&nbsp;3&nbsp;mm bearing, fit the bearing int othe 3D printed wheel now,
- push the handle of the plinky-plonky mechanism into the offset hole in the 3D pritned wheel then, with the grub screw hole and the flat on the stepper motor spindle uppermost, push the stepper motor spindle into the centre of the 3D printed wheel from the other side of its mounting bracket (i.e. so the spindle goes through the large central hole in the motor mounting bracket) and, as you do so, pass the stepper motor wires through the hole in the bottom left of the bracket and into the area of the 3D printed body where the boards will be mounted,
- fix the stepper motor to the mounting bracket with the two M3&nbsp;x&nbsp;5&nbsp;mm hex head bolts,
- screw the grub screw into the 3D printed wheel against the flat of the stepper motor spindle, making sure that there is a gap between the wheel and the stepper motor mounting bracket as you tighten the grub screw,
- using some 7-strand signal wire and heat-shrink as appropriate, make a short "Y" assembly with the 1&nbsp;kOhm resistor in series with one of the legs of the Y, all legs terminated in single 0.1 inch pitch connectors (see picture below),
- using this, the remaining 7-strand wire and the 0.1&nbsp;inch pitch connectors, wire up the ESP32 board, the TMC2209 board and the stepper motor according to the schematic below, bringing 5&nbsp;V from the output of the voltage regulator and 12&nbsp;V from the input of the voltage regulator (you may chose different IO pins on the ESP32 board if you wish, these will be set later when the software for the ESP32 is compiled, though note that pin&nbsp;8 is not used since there is a blue LED on the board connected to this pin and pin&nbsp;20 is not used as it is internally connected to the RF),
- wire the panel-mount power socket to the input of the voltage regulator board, with the central pin of the panel mount socket to the positive,
- fit the panel-mount power socket into its mounting hole in the 3D printed body,
- plug a 12&nbsp;V plug-top power supply into the power socket and you should get a red light on the ESP32 board,
- fix the 3D printed stepper motor cover to the stepper motor mounting bracket with two of the M3&nbsp;x&nbsp;10&nbsp;mm hex head bolts,
- fix the 3D printed lid into position over the electronics with the remaining M3&nbsp;x&nbsp;10&nbsp;mm hex head bolt.

<img src="pictures_for_readme/mechanism_mounted.jpg" alt="Mechanism mounted" style="width:35%; height:auto;"> <img src="pictures_for_readme/sticky_pads.jpg" alt="Sticky pads" style="width:22%; height:auto;"> <img src="pictures_for_readme/voltage_regulator_board_fitted.jpg" alt="Voltage regulator board fitted" style="width:32%; height:auto;">

<img src="pictures_for_readme/boards_clipped_in.jpg" alt="Boards clipped in" style="width:23.5%; height:auto;"> <img src="pictures_for_readme/stepper_motor_wires_ready.jpg" alt="Stepper motor wires trimmed and connector fitted" style="width:33%; height:auto;"> <img src="pictures_for_readme/wheel_grub_screw_hole_tapped.jpg" alt="Wheel grub screw hole tapped" style="width:38%; height:auto;">

<img src="pictures_for_readme/wheel_and_stepper_motor_fixed.jpg" alt="Wheel and stepper motor fixed" style="width:28%; height:auto;"> <img src="pictures_for_readme/y_wire_before_heatshrink.jpg" alt="Y-wire before heatshrink" style="width:37%; height:auto;"> <img src="pictures_for_readme/y_wire_after_heatshrink.jpg" alt="Y-wire after heatshrink" style="width:30%; height:auto;">

<img src="pictures_for_readme/schematic.png" alt="Schematic" style="width:100%; height:auto;">

<img src="pictures_for_readme/wiring_1.jpg" alt="Wires cut to length and connectors fitted" style="width:44%; height:auto;"> <img src="pictures_for_readme/wiring_2.jpg" alt="Wiring completed" style="width:30%; height:auto;"> <img src="pictures_for_readme/12v_input.jpg" alt="12 V input socket with wires attached and heat-shrinked" style="width:12%; height:auto;">

<img src="pictures_for_readme/12v_input_fitted.jpg" alt="12 V input socket fitted" style="width:27%; height:auto;"> <img src="pictures_for_readme/red_led_lit.jpg" alt="12 V plugged in, LED lit" style="width:36%; height:auto;"> <img src="pictures_for_readme/covers_fitted.jpg" alt="Covers fitted" style="width:32%; height:auto;">

# Bring-Up
Bring-up goes as follows:
- plug a PC on which the [ESP-IDF software environment](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/get-started/index.html) has been installed into the USB socket of the ESP32 board: a USB device should appear on the PC (e.g. `/dev/ttyACM0` on Linux); there is no need to plug 12&nbsp;V power into the plinky-plonky just yet as it should be able to draw sufficient power for these initial tests from the USB,
- build the software and download it to the ESP32 via USB; if you have wired up different GPIO pins on the ESP32 to those in the schematic above, change the values in [Kconfig.projbuild](software/esp32/Kconfig.projbuild) before compiling;
- when the code runs on the ESP32 nothing much should happen, you will need an Android application for that, but if you see a splurge of "STALL" in the logged output of the ESP32 then you _do_ need to plug 12&nbsp;V power into the plinky-plonky.
- 

