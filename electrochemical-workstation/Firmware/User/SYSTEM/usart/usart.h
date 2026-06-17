#ifndef __USART_H
#define __USART_H
#include "stdio.h"
#include "sys.h"

/* USART1 receive buffer and status for CRLF-terminated frames. */
#define USART_REC_LEN  			200
#define EN_USART1_RX 			1

extern u8  USART_RX_BUF[USART_REC_LEN]; 	/* Circular buffer holding assembled RX bytes */
extern u16 USART_RX_STA;         			/* Bitfield: bit15=complete, bit14=CR received, bits13-0=length */
extern UART_HandleTypeDef UART1_Handler; 	/* USART1 HAL handle */

#define RXBUFFERSIZE   1 					/* HAL interrupt receives 1 byte at a time */
extern u8 aRxBuffer[RXBUFFERSIZE];			/* Single-byte DMA/IT RX buffer */

/* Initialise USART1 at the given baud rate and arm interrupt-driven reception. */
void uart_init(u32 bound);
#endif
