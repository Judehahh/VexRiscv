## JTAG
set_property PACKAGE_PIN	G1	 [get_ports tck]
set_property IOSTANDARD LVCMOS33 [get_ports tck]
set_property CLOCK_DEDICATED_ROUTE FALSE [get_nets tck_IBUF]

set_property PACKAGE_PIN	G4	 [get_ports tms]
set_property IOSTANDARD LVCMOS33 [get_ports tms]

set_property PACKAGE_PIN	H4	 [get_ports tdo]
set_property IOSTANDARD LVCMOS33 [get_ports tdo]
set_property PULLUP		true	 [get_ports tdo]

set_property PACKAGE_PIN	G2	 [get_ports tdi]
set_property IOSTANDARD LVCMOS33 [get_ports tdi]

set_property PACKAGE_PIN	H1	 [get_ports trst]
set_property IOSTANDARD LVCMOS33 [get_ports trst]
set_property	PULLUP	true	 [get_ports trst]

## serial:0.tx
set_property PACKAGE_PIN D4 [get_ports serial_tx]
set_property IOSTANDARD LVCMOS33 [get_ports serial_tx]
## serial:0.rx
set_property PACKAGE_PIN C4 [get_ports serial_rx]
set_property IOSTANDARD LVCMOS33 [get_ports serial_rx]

## clk100:0
set_property PACKAGE_PIN E3 [get_ports clk100]
set_property IOSTANDARD LVCMOS33 [get_ports clk100]

## cpu_reset:0
set_property PACKAGE_PIN C12 [get_ports cpu_reset]
set_property IOSTANDARD LVCMOS33 [get_ports cpu_reset]

## user_led:0
set_property PACKAGE_PIN H17 [get_ports user_led0]
set_property IOSTANDARD LVCMOS33 [get_ports user_led0]
## user_led:1
set_property PACKAGE_PIN K15 [get_ports user_led1]
set_property IOSTANDARD LVCMOS33 [get_ports user_led1]
## user_led:2
set_property PACKAGE_PIN J13 [get_ports user_led2]
set_property IOSTANDARD LVCMOS33 [get_ports user_led2]
## user_led:3
set_property PACKAGE_PIN N14 [get_ports user_led3]
set_property IOSTANDARD LVCMOS33 [get_ports user_led3]

## user_sw:0
set_property PACKAGE_PIN J15 [get_ports user_sw0]
set_property IOSTANDARD LVCMOS33 [get_ports user_sw0]
## user_sw:1
set_property PACKAGE_PIN L16 [get_ports user_sw1]
set_property IOSTANDARD LVCMOS33 [get_ports user_sw1]
## user_sw:2
set_property PACKAGE_PIN M13 [get_ports user_sw2]
set_property IOSTANDARD LVCMOS33 [get_ports user_sw2]
## user_sw:3
set_property PACKAGE_PIN R15 [get_ports user_sw3]
set_property IOSTANDARD LVCMOS33 [get_ports user_sw3]

## user_btn:0
set_property PACKAGE_PIN M18 [get_ports user_btn0]
set_property IOSTANDARD LVCMOS33 [get_ports user_btn0]
## user_btn:1
set_property PACKAGE_PIN P17 [get_ports user_btn1]
set_property IOSTANDARD LVCMOS33 [get_ports user_btn1]
## user_btn:2
set_property PACKAGE_PIN N17 [get_ports user_btn2]
set_property IOSTANDARD LVCMOS33 [get_ports user_btn2]
## user_btn:3
set_property PACKAGE_PIN M17 [get_ports user_btn3]
set_property IOSTANDARD LVCMOS33 [get_ports user_btn3]

set_property INTERNAL_VREF 0.75 [get_iobanks 14]

create_clock -period 10.000 -name clk100 [get_nets clk100]

set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 4 [current_design]
