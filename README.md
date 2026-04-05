# Introduction
For Christmas 2025 Santa brought Hazel a music box mechanism, henceforth referred to as a "plinky-plonky".  Since it was difficult to turn at speed, I did a quick [motorised version](https://www.youtube.com/watch?v=XHdWuA18UNk) with some bits I was able to get hold of quickly: a motor controller with a speed knob on it and a 5&nbsp;V motor which purported to be silent.  The motor was not silent at all.

Since then I have made a [working Camberwick Green musical box](https://www.meades.org/misc/musical_box/musical_box.html) using one of these mechanisms, employing several varieties of stepper motor and a TMC2209 stepper motor controller.  Hence this repository describes how to make the updated version of the motorised plink-plonky, employing a much quieter stepper motor, TMC2209, ESP32 and using Bluetooth for control.

You can find a video of it in action on [YouTube](https://youtu.be/Unc9a-GsFBE); read on to find out what the lead weights are for.

In order to make this motorised plinky-plonky you will need to be able to:

- 3D print in FDM,
- solder,
- wire-up a small amount of electronics,
- write, or at least compile, C code (based on [ESP-IDF](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/get-started/index.html) for the ESP32),
- write, or at least compile, an Android application to drive the Android Bluetooth API, or download and install the rather spiffy one from the [Releases](https://github.com/RobMeades/plinky-plonky/releases) section of this repo that Google Gemini wrote with a lot of prompting,
- do a tiny amount of woodwork (cutting circular shapes in plywood, sanding, glueing, varnishing).

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
- a largish magnetic flat-head screwdriver,
- an Android phone,
- a coping saw/jigsaw/clever-Dremel-thingy or other means of cutting circular shapes in thin plywood,
- a drill with 3&nbsp;mm and 10&nbsp;mm drill bits,
- fine grade sandpaper,
- a sanding block,
- optional: a very fine modellers paint brush.

# 3D Printed Parts
The 3D printed parts, along with exported `.stl`, are available in the [blender](/blender) folder; see there for printing instructions.

# Parts List
The following parts are required:

- the 3D printed parts from the [blender](blender) folder (which will require filament of your choice),
- a 15&nbsp;note plinky-plonky mechanism with fixing screws: the 3D printed parts are designed for one of [these](https://www.ebay.co.uk/itm/397247369927),
- an ESP32 board with headers soldered-in on the component side: the 3D printed parts are designed for one of [these](https://www.aliexpress.com/item/1005005780805655.html), you could of course modify them to take any small ESP32 board,
- a BigTreeTech TMC2209 board, i.e. one of [these](https://www.aliexpress.com/item/33028050145.html), any "sticky out" pins on the reverse lopped off and heatsink fitted,
- a 12/24&nbsp;V&nbsp;DC to 5&nbsp;V&nbsp;DC regulator: the 3D printed parts are designed for one of [these](https://www.aliexpress.com/item/1005010692851197.html), you could of course modify them to take any small regulator board; the current requirements for the 5&nbsp;V supply are negligible,
- a NEMA17 stepper motor: the 3D printed parts are designed for one of [these](https://www.amazon.co.uk/dp/B0D7PBB17L), frame size 42&nbsp;mm&nbsp;x&nbsp;42&nbsp;mm with M3 threaded mounting holes, frame 20&nbsp;mm deep, shaft 5&nbsp;mm diameter and 20&nbsp;mm long, with flat,
- an AC mains plug-top power supply capable of delivering up to 2&nbsp;Amps at between 12&nbsp;and&nbsp;24&nbsp;V&nbsp;DC, terminating in a 5.5&nbsp;mm diameter barrel connector with a 2.1&nbsp;mm diameter pin, centre-positive,
- an M3 hex-head grub screw (any length, the longer the better),
- 2 off M3&nbsp;x&nbsp;5&nbsp;mm hex head bolts,
- 3 off M3&nbsp;x&nbsp;10&nbsp;mm hex head bolts,
- 4 off M3&nbsp;x&nbsp;5&nbsp;mm pan head bolts,
- 3 off M3&nbsp;x&nbsp;10&nbsp;mm to 15&nbsp;mm pan head bolts,
- a panel-mount DC power socket with 2.1&nbsp;mm diameter pin, 5.5&nbsp;mm diameter barrel outer,
- 1 off 1&nbsp;kOhm resistor,
- a small amount of 7-strand wire in  a few shades of red/orange if you can (input DC Voltage, 5&nbsp;V and 3.3&nbsp;V), black and a signal colour of your choice (e.g. yellow or white or blue),
- a small amount (e.g. 100&nbsp;mm) of 3&nbsp;mm diameter heat-shrink,
- a small amount of flux-cored solder,
- 0.1&nbsp;inch pitch housings (1-way, 2-way and 4-way) plus female pins to go with,
- 2 off pieces (e.g. 10&nbsp;mm square) of double-sided sticky pad, tape or individual pads,
- a small amount (e.g. 400&nbsp;x&nbsp;250&nbsp;mm) of thin (e.g. 3&nbsp;mm thick) plywood,
- a small amount of wide (e.g. 30&nbsp;mm wide) edging that matches the plywood,
- optional: 1 off 4&nbsp;x&nbsp;8&nbsp;x&nbsp;3&nbsp;mm (inside diameter x outside diameter x thickness) bearing: I found that the sheath on the handle of the plinky-plonky mechanism rattled irritatingly so I took it off and slotted the handle into one of these bearings in the 3D printed wheel instead,
- PVA glue,
- possibly a little cyanoacrylate glue (AKA superglue),
- varnish or paint of your choice,
- a small amount of metallic paint (e.g. Humbrol&nbsp;11),
- optional: stick-on felt pads,
- optional: some 0.5&nbsp;kg lead weights e.g. [these from eBay](https://www.ebay.co.uk/itm/145559704455).

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
- push the handle of the plinky-plonky mechanism into the offset hole in the 3D printed wheel then, with the grub screw hole and the flat on the stepper motor spindle uppermost, push the stepper motor spindle into the centre of the 3D printed wheel from the other side of its mounting bracket (i.e. so the spindle goes through the large central hole in the motor mounting bracket) and, as you do so, pass the stepper motor wires through the hole in the bottom left of the bracket and into the area of the 3D printed body where the boards will be mounted,
- fix the stepper motor to the mounting bracket with the two M3&nbsp;x&nbsp;5&nbsp;mm hex head bolts,
- screw the grub screw into the 3D printed wheel against the flat of the stepper motor spindle, making sure that there is a gap between the wheel and the stepper motor mounting bracket as you tighten the grub screw,
- using some 7-strand signal wire and heat-shrink as appropriate, make a short "Y" assembly with the 1&nbsp;kOhm resistor in series with one of the legs of the Y, all legs terminated in single 0.1&nbsp;inch pitch connectors (see picture below),
- using this, the remaining 7-strand wire and the 0.1&nbsp;inch pitch connectors, wire up the ESP32 board, the TMC2209 board and the stepper motor according to the schematic below, bringing 5&nbsp;V from the output of the voltage regulator and 12&nbsp;to&nbsp;24&nbsp;V from the input of the voltage regulator (you may chose different IO pins on the ESP32 board if you wish, these will be set later when the software for the ESP32 is compiled, though note that pin&nbsp;8 is not used since there is a blue LED on the board connected to this pin and pin&nbsp;20 is not used as it is internally connected to the RF inside the chip),
- wire the panel-mount power socket to the input of the voltage regulator board, with the central pin of the panel mount socket to the positive, adding heat-shrink to cover any exposed connector (since there is little room in the box),
- fit the panel-mount power socket into its mounting hole in the 3D printed body,
- plug an AC mains 12&nbsp;/nbsp;24&nbsp;V DC plug-top power supply into the power socket and you should see a red light on the ESP32 board,
- fix the 3D printed stepper motor cover to the stepper motor mounting bracket with two of the M3&nbsp;x&nbsp;10&nbsp;mm hex head bolts,
- fix the 3D printed lid into position over the electronics with the remaining M3&nbsp;x&nbsp;10&nbsp;mm hex head bolt,
- proceed to "Bring Up" below.

<img src="/pictures_for_readme/mechanism_mounted.jpg" alt="Mechanism mounted" style="width:35%; height:auto;"> <img src="/pictures_for_readme/sticky_pads.jpg" alt="Sticky pads" style="width:22%; height:auto;"> <img src="/pictures_for_readme/voltage_regulator_board_fitted.jpg" alt="Voltage regulator board fitted" style="width:32%; height:auto;">

<img src="/pictures_for_readme/boards_clipped_in.jpg" alt="Boards clipped in" style="width:23.5%; height:auto;"> <img src="/pictures_for_readme/stepper_motor_wires_ready.jpg" alt="Stepper motor wires trimmed and connector fitted" style="width:33%; height:auto;"> <img src="/pictures_for_readme/wheel_grub_screw_hole_tapped.jpg" alt="Wheel grub screw hole tapped" style="width:38%; height:auto;">

<img src="/pictures_for_readme/wheel_and_stepper_motor_fixed.jpg" alt="Wheel and stepper motor fixed" style="width:28%; height:auto;"> <img src="/pictures_for_readme/y_wire_before_heatshrink.jpg" alt="Y-wire before heatshrink" style="width:37%; height:auto;"> <img src="/pictures_for_readme/y_wire_after_heatshrink.jpg" alt="Y-wire after heatshrink" style="width:30%; height:auto;">

<img src="/pictures_for_readme/schematic.png" alt="Schematic" style="width:100%; height:auto;">

<img src="/pictures_for_readme/wiring_1.jpg" alt="Wires cut to length and connectors fitted" style="width:44%; height:auto;"> <img src="/pictures_for_readme/wiring_2.jpg" alt="Wiring completed" style="width:30%; height:auto;"> <img src="/pictures_for_readme/12_to_24v_input.jpg" alt="power socket with wires attached and heat-shrinked" style="width:12%; height:auto;">

<img src="/pictures_for_readme/12_to_24v_input_fitted.jpg" alt="power socket fitted" style="width:27%; height:auto;"> <img src="/pictures_for_readme/red_led_lit.jpg" alt="power plugged in, LED lit" style="width:36%; height:auto;"> <img src="/pictures_for_readme/covers_fitted.jpg" alt="Covers fitted" style="width:32%; height:auto;">

# Bring Up
Bring up goes as follows:

- plug a PC on which the [ESP-IDF software environment](https://docs.espressif.com/projects/esp-idf/en/stable/esp32/get-started/index.html) has been installed into the USB socket of the ESP32 board: a USB device should appear on the PC (e.g. `/dev/ttyACM0` on Linux); there is no need to plug external power into the plinky-plonky just yet as it should be able to draw sufficient power for these initial tests from the USB,
- build the software that is contained in the [software/esp32](/software/esp32) directory and download it to the ESP32 via USB; if you have wired up different GPIO pins on the ESP32 to those in the schematic above, change the values in [Kconfig.projbuild](/software/esp32/main/Kconfig.projbuild) before compiling,
- when the code runs on the ESP32 nothing much should happen, you will need an Android application for that, but if you see a splurge of "STALL" in the logged output of the ESP32 then you _do_ need to supply external power to the plinky-plonky,
- either install the pre-built Android `.apk` from the [Releases](https://github.com/RobMeades/plinky-plonky/releases) section of this repository on an Android phone or, if you wish to build/modify the application yourself, follow the instructions in the [software/android](/software/android) directory; Android will show a `Play Protect` warning, which is normal for an independent, open-source project: to install tap `More Details` and then `Install Anyway`,
- make sure you have Bluetooth enabled on the Android phone, hold the phone upright (portait orientation) and launch the application,
- give the application the permissions it asks for (required to access Bluetooth) and, with the plinky-plonky powered-up, use the application to scan for and connect to the plinky-plonky; if the Android phone fails to find the plinky-plonky, try disabling and then re-enabling Bluetooth on the phone and trying again (Android Bluetooth is a mess),
- press `Play` to have the motor run, `Stop` to stop it, adjust the speed using the knob or turn the phone landscape for the ability to generate complicated speed patterns,
- if you find that the plinky-plonky rattles at certain speeds, even when you have used the bearing approach, that will likely be because the handle can be slightly loose in the bearing: should that be the case, drop a _very_ tiny amount of cyanoacrylate glue between the handle and the inner face of the bearing to stop it rattling around, being _extremely_ careful not to get any glue on the runners of the bearing as you do so,
- rattles/resonance can also be removed by going around and gently tighting the fixing bolts,
- if that fails, since resonances can be in different components at different frequencies, I purchased some lead weights and kept three to hand for placing on whatever bit of the plinky-plonky needed damping at that running frequency (obviously wash your hands after handling lead),
- when you are satisfied that the plinky-plonky runs sweetly, push the 3D printed USB cover into the rectangular hole in the 3D printed body to prevent anything getting into the USB connector and proceed to "Finishing Off" below.

# Finishing Off
In order to give the plinky-plonky a solid yet resonant (in a good way) base:

- cut two pieces of thin plywood to the shape of the 3D printed `base_outer` (see pictures below),
- into one of these pieces drill three 3&nbsp;mm diameter holes to match the positions of the holes in the bottom of the 3D printed body, orienting that body nicely,
- into the other piece drill three rather wider (e.g. 10&nbsp;mm diameter) access holes in the same locations,
- glue the two pieces of plywood, in the same orientation, either side of `base_outer` with PVA, to make a kind of giant Oreo cookie, ensuring that the holes and the access holes align,
- glue an edging strip that matches the colour of the plywood around `base_outer` with PVA,
- when the glue has set, rub the whole thing down with fine sandpaper,
- varnish/finish in whatever way pleases you,
- to make the next step easier, create the threads in each of the holes in the bottom of the 3D printed body using one of the longer M3 pan head bolts,
- using the access holes to get at the 3&nbsp;mm diameter holes, attach the wooden/plastic base to the bottom of the 3D printed body with the longer M3 pan head bolts; a magnetic flat-head screwdriver is your friend,
- if you find that the plink-plonky resonates somewhat when running placed on a hard surface, stick-on felt pads or rubber feet help.

Finally, pick out the lettering embossed into the 3D printed stepper motor cover in metallic paint using a very fine modeller's paint brush.

<img src="/pictures_for_readme/plywood_cut_and_drilled.jpg" alt="Plywood cut and drilled" style="width:29%; height:auto;"> <img src="/pictures_for_readme/box.jpg" alt="Box, varnished" style="width:30%; height:auto;"> <img src="/pictures_for_readme/completed.jpg" alt="Completed" style="width:38%; height:auto;">

