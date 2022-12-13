BLE_2_EPaper<br>
-----------------------------------
Copyright (c) 2022 BitBank Software, Inc.<br>
Written by Larry Bank<br>
larry@bitbanksoftware.com<br>
<br>
**This collection of code is a place to experiment with sending images from one system to another over BLE and displaying them on e-paper. The ultimate use will be to have low power MCUs listening for connections and efficiently receiving images wirelessly either from computers or other MCUs. For now I have an Arduino Nano 33 BLE receiver with the e-paper attached as well as hacked Hanshow electronic shelf labels. On the sending side I have a MacOS GUI application with drag-drop image handling and a slightly less functional Android app which can also send images. Feel free to contribute code or ideas to improve any or all of this project**<br>
<br>
![BLE_2_EPaper](/demo.jpg?raw=true "BLE_2_EPaper")
<br>
<br>
![Fritzing Diagram](/ble_fritzing.png?raw=true "Fritzing Diagram")
<br>
The image above depicts the connections to an Arduino Nano 33 BLE board for this project. The following pins are used:<br>
- Button 1 = D2<br>
- Button 2 = D3<br>
- E-Paper CLK = D13<br>
- E-Paper CS = D10<br>
- E-Paper MOSI = D12<br>
- E-Paper RST = D14/A0<br>
- E-Paper BUSY = D15/A1<br>
- E-Paper D/C = D16/A2<br>

Other pin combinations are certainly possible, but those are the ones I chose for the prototype.<br>
~                                                                               

