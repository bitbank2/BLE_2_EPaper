ESLImageTransfer
----------------
Copyright (c) 2021 BitBank Software, Inc.<br>
Written by Larry Bank<br>
bitbank@pobox.com<br>

What is it?
-----------
An Android native application to send image data over BLE to electronic shelf
labels. I've also added the ability to generate local weather info as a bitmap suitable for sending to the ESL (see below).<br>
<br>
![Weather](/image_transfer.jpg?raw=true "Weather Info")
<br>

How do I use it?
----------------
This repo is an Android App shared as Java source code. You need Google's Android Studio to build it. A debug APK (application install package) is provided in the root directory of this repo so that you can run it without having to build it. To install the pre-built APK on an Android device, you'll need to either copy+open the APK file on the device or run ADB (Android debug bridge) with the install command over a USB connection.<br>

