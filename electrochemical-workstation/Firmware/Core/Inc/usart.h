/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file    usart.h
  * @brief   This file contains all the function prototypes for
  *          the usart.c file
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2025 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
  #include "stdio.h"
#include "sys.h"
  #include <string.h>
/* USER CODE END Header */
/* Define to prevent recursive inclusion -------------------------------------*/
#ifndef __USART_H__
#define __USART_H__

#ifdef __cplusplus
extern "C" {
#endif

/* Includes ------------------------------------------------------------------*/
#include "main.h"

/* USER CODE BEGIN Includes */

/* USER CODE END Includes */

extern UART_HandleTypeDef huart1;

extern UART_HandleTypeDef huart2;

extern UART_HandleTypeDef huart3;

 /* USER CODE BEGIN Private defines */
	#define RX_BUFFER_SIZE 256     /* DMA RX buffer size (shared by USART1 and USART3) */
	extern uint8_t rx_buffer_uart1[RX_BUFFER_SIZE];  /* USART1 (legacy / debug) DMA RX buffer */
	extern uint8_t rx_buffer[RX_BUFFER_SIZE];         /* USART3 (BLE module) DMA RX buffer */
	extern volatile uint16_t rx_index;                /* Write index into the DMA RX buffer */
	extern int16_t payload[50]; /* Decoded 16-bit parameter words from the host frame */

 /* USER CODE END Private defines */

void MX_USART1_UART_Init(void);
void MX_USART2_UART_Init(void);
void MX_USART3_UART_Init(void);

/* USER CODE BEGIN Prototypes */

#define USART_REC_LEN   200                     /* Receive buffer size in bytes */
#define USART_EN_RX     1                       /* Enable (1) or disable (0) USART1 RX */
#define RXBUFFERSIZE    1                       /* Single-byte HAL interrupt RX */
extern uint8_t  g_usart_rx_buf[USART_REC_LEN];  /* USART1 ring buffer */
extern uint16_t g_usart_rx_sta;                 /* bit15=frame complete, bit14=CR received, [13:0]=length */
extern uint8_t g_rx_buffer[RXBUFFERSIZE];       /* HAL USART interrupt RX byte */

/* Binary frame protocol helpers */
void SendFrame(uint8_t command, uint8_t *data, uint8_t dataLength);
void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart);
void ParseFrame(uint8_t *frame);
void print_usart_rx_buf_via_uart(void);
uint8_t CalculateChecksum(const uint8_t *data, uint8_t len);
uint8_t CalculateChecksum1(const uint8_t *data, uint8_t len);
void ProcessReceivedData(uint8_t *data, uint16_t len);
void SendData(uint8_t cmd, uint8_t *data, uint8_t len);
void SendData1(uint8_t cmd, uint8_t *data, uint8_t len);
void Send_AT_Command(char *cmd);
void Configure_BLE_Module(void);

/* USER CODE END Prototypes */

#ifdef __cplusplus
}
#endif

#endif /* __USART_H__ */
