`timescale 1ns / 1ps

module toplevel(
    input  wire clk27,
    input  wire cpu_reset, //active high, SW1

    input  wire serial_rx,
    output wire serial_tx,

    input  wire user_btn0, // SW2

    output wire user_led0,
    output wire user_led1,
    output wire user_led2,
    output wire user_led3,
    output wire user_led4,
    output wire user_led5
  );

  wire [31:0] io_gpioA_read;
  wire [31:0] io_gpioA_write;
  wire [31:0] io_gpioA_writeEnable;

  wire io_asyncReset = cpu_reset;

  assign {user_led5,user_led4,user_led3,user_led2,user_led1,user_led0} = io_gpioA_write[5:0];
  assign io_gpioA_read[0] = {user_btn0};

  Murax core (
    .io_asyncReset(io_asyncReset),
    .io_mainClk (clk27),
    .io_jtag_tck(1'b0),
    .io_jtag_tdi(1'b0),
    .io_jtag_tdo(),
    .io_jtag_tms(1'b0),
    .io_gpioA_read       (io_gpioA_read),
    .io_gpioA_write      (io_gpioA_write),
    .io_gpioA_writeEnable(io_gpioA_writeEnable),
    .io_uart_txd(serial_tx),
    .io_uart_rxd(serial_rx)
  );
endmodule
