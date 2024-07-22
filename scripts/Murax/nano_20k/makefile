ROOT=../../..
DEVICE=GW2AR-LV18QN88C8/I7

TOP=Murax

all : build/latest.bit

$(ROOT)/$(TOP).v : toplevel.v
	(cd $(ROOT); sbt "runMain vexriscv.demo.Murax_nano20k")

build/verilog : $(ROOT)/$(TOP).v
	mkdir -p build
	cp -v $(ROOT)/$(TOP).v $(ROOT)/$(TOP).v_* build

build/latest.json : build/verilog
	yosys -p "read_verilog toplevel.v build/$(TOP).v; synth_gowin -json build/latest.json"

build/pnr.json : build/latest.json nano_20k.cst
	nextpnr-himbaechel	\
		--json build/latest.json	\
		--write build/pnr.json 		\
		--device $(DEVICE)			\
		--vopt family=GW2A-18C 		\
		--vopt cst=nano_20k.cst

build/latest.fs : build/pnr.json
	gowin_pack -d GW2A-18C -o build/latest.fs build/pnr.json

prog : build/latest.fs
	./write_fpga

flash : build/latest.fs
	./write_flash

.PHONY: clean
clean :
	rm -rf build
	mkdir build

clean-all : clean
	rm -f $(ROOT)/$(TOP).v
	rm -f $(ROOT)/$(TOP).v_*
