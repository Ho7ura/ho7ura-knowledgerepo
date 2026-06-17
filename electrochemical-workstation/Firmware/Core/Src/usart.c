/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file    usart.c
  * @brief   This file provides code for the configuration
  *          of the USART instances.
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
  #include <string.h>
  #include <stdio.h>
  #include "sys.h"
  #include "ecw.h"
/* USER CODE END Header */
/* Includes ------------------------------------------------------------------*/
#include "usart.h"
#include "stm32f1xx_hal.h"

/* USER CODE BEGIN 0 */
uint8_t rx_buffer[RX_BUFFER_SIZE];
uint8_t rx_buffer_uart1[RX_BUFFER_SIZE];
volatile uint16_t rx_index;
int16_t payload[50];

static uint8_t GetExpectedPayloadLength(Sweep_Mode_Type cmd)
{
    switch (cmd)
    {
        case DORMANT:
        case PAUSE:
            return 0u;
        case IT:
            return 12u;
        case CV:
            return 18u;
        case DPV:
            return 20u;
        case SWV:
            return 16u;
        case CTLPANEL:
            return 4u;
        default:
            return 0xFFu;
    }
}
////////////////////////////////////////////////////////////////////////////////// 	 

#if SYSTEM_SUPPORT_OS
#include "includes.h" 
#endif

#if 1
#pragma import(__use_no_semihosting)             

struct __FILE 
{ 
	int handle; 

}; 

FILE __stdout;       
void _sys_exit(int x)
{
	x = x;
}
int fputc(int ch, FILE *f)
{      
	while((USART3->SR&0X40)==0);
    USART3->DR = (u8) ch;      
	return ch;
}
#endif 
#if USART_EN_RX

uint8_t g_usart_rx_buf[USART_REC_LEN];

/*  
 *  bit15:      
 *  bit14:      
 *  bit13~0:    
*/
uint16_t g_usart_rx_sta = 0;

uint8_t g_rx_buffer[RXBUFFERSIZE];

/* USER CODE END 0 */

UART_HandleTypeDef huart1;
UART_HandleTypeDef huart2;
DMA_HandleTypeDef hdma_usart1_rx;
DMA_HandleTypeDef hdma_usart2_rx;
UART_HandleTypeDef huart3;
DMA_HandleTypeDef hdma_usart3_rx;

/* USART1 init function */

void MX_USART1_UART_Init(void)
{

  /* USER CODE BEGIN USART1_Init 0 */

  /* USER CODE END USART1_Init 0 */

  /* USER CODE BEGIN USART1_Init 1 */

  /* USER CODE END USART1_Init 1 */
  huart1.Instance = USART1;
  huart1.Init.BaudRate = 115200;
  huart1.Init.WordLength = UART_WORDLENGTH_8B;
  huart1.Init.StopBits = UART_STOPBITS_1;
  huart1.Init.Parity = UART_PARITY_NONE;
  huart1.Init.Mode = UART_MODE_TX_RX;
  huart1.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart1.Init.OverSampling = UART_OVERSAMPLING_16;
  if (HAL_UART_Init(&huart1) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN USART1_Init 2 */
  __HAL_UART_ENABLE_IT(&huart1, UART_IT_IDLE);
    HAL_UART_Receive_DMA(&huart1, rx_buffer_uart1, RX_BUFFER_SIZE);
    //4434HAL_UART_Receive_IT(&huart1, (uint8_t *)g_rx_buffer, RXBUFFERSIZE);

  /* USER CODE END USART1_Init 2 */

}
/* USART2 init function */

void MX_USART3_UART_Init(void)
{

  /* USER CODE BEGIN USART3_Init 0 */

  /* USER CODE END USART3_Init 0 */

  /* USER CODE BEGIN USART3_Init 1 */

  /* USER CODE END USART3_Init 1 */
  huart3.Instance = USART3;
  huart3.Init.BaudRate = 115200;
  huart3.Init.WordLength = UART_WORDLENGTH_8B;
  huart3.Init.StopBits = UART_STOPBITS_1;
  huart3.Init.Parity = UART_PARITY_NONE;
  huart3.Init.Mode = UART_MODE_TX_RX;
  huart3.Init.HwFlowCtl = UART_HWCONTROL_NONE;
  huart3.Init.OverSampling = UART_OVERSAMPLING_16;
  if (HAL_UART_Init(&huart3) != HAL_OK)
  {
    Error_Handler();
  }
  /* USER CODE BEGIN USART3_Init 2 */
    __HAL_UART_ENABLE_IT(&huart3, UART_IT_IDLE);
    HAL_UART_Receive_DMA(&huart3, rx_buffer, RX_BUFFER_SIZE);

  /* USER CODE END USART3_Init 2 */

}

void HAL_UART_MspInit(UART_HandleTypeDef* uartHandle)
{

  GPIO_InitTypeDef GPIO_InitStruct = {0};
  if(uartHandle->Instance==USART1)
  {
  /* USER CODE BEGIN USART1_MspInit 0 */

  /* USER CODE END USART1_MspInit 0 */
    /* USART1 clock enable */
    __HAL_RCC_USART1_CLK_ENABLE();

    __HAL_RCC_GPIOA_CLK_ENABLE();
    /**USART1 GPIO Configuration
    PA9     ------> USART1_TX
    PA10     ------> USART1_RX
    */
    GPIO_InitStruct.Pin = GPIO_PIN_9;
    GPIO_InitStruct.Mode = GPIO_MODE_AF_PP;
    GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_Init(GPIOA, &GPIO_InitStruct);

    GPIO_InitStruct.Pin = GPIO_PIN_10;
    GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
    GPIO_InitStruct.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(GPIOA, &GPIO_InitStruct);


    hdma_usart1_rx.Instance = DMA1_Channel5;
    hdma_usart1_rx.Init.Direction = DMA_PERIPH_TO_MEMORY;
    hdma_usart1_rx.Init.PeriphInc = DMA_PINC_DISABLE;
    hdma_usart1_rx.Init.MemInc = DMA_MINC_ENABLE;
    hdma_usart1_rx.Init.PeriphDataAlignment = DMA_PDATAALIGN_BYTE;
    hdma_usart1_rx.Init.MemDataAlignment = DMA_MDATAALIGN_BYTE;
    hdma_usart1_rx.Init.Mode = DMA_CIRCULAR;
    hdma_usart1_rx.Init.Priority = DMA_PRIORITY_LOW;
    if (HAL_DMA_Init(&hdma_usart1_rx) != HAL_OK)
    {
      Error_Handler();
    }

    __HAL_LINKDMA(uartHandle,hdmarx,hdma_usart1_rx);


    HAL_NVIC_SetPriority(USART1_IRQn, 1, 0);
    HAL_NVIC_EnableIRQ(USART1_IRQn);
  /* USER CODE BEGIN USART1_MspInit 1 */
  #if EN_USART1_RX
		HAL_NVIC_EnableIRQ(USART1_IRQn);				
		HAL_NVIC_SetPriority(USART1_IRQn,3,3);			
#endif	

  /* USER CODE END USART1_MspInit 1 */
  }
  else if(uartHandle->Instance==USART3)
  {
  /* USER CODE BEGIN USART3_MspInit 0 */

  /* USER CODE END USART3_MspInit 0 */
    /* USART3 clock enable */
    __HAL_RCC_USART3_CLK_ENABLE();

    __HAL_RCC_GPIOB_CLK_ENABLE();
    /**USART3 GPIO Configuration
    PB10     ------> USART3_TX
    PB11     ------> USART3_RX
    */
    GPIO_InitStruct.Pin = GPIO_PIN_10;
    GPIO_InitStruct.Mode = GPIO_MODE_AF_PP;
    GPIO_InitStruct.Speed = GPIO_SPEED_FREQ_HIGH;
    HAL_GPIO_Init(GPIOB, &GPIO_InitStruct);

    GPIO_InitStruct.Pin = GPIO_PIN_11;
    GPIO_InitStruct.Mode = GPIO_MODE_INPUT;
    GPIO_InitStruct.Pull = GPIO_NOPULL;
    HAL_GPIO_Init(GPIOB, &GPIO_InitStruct);

    /* USART3 DMA Init */
    /* USART3_RX Init */
    hdma_usart3_rx.Instance = DMA1_Channel3;
    hdma_usart3_rx.Init.Direction = DMA_PERIPH_TO_MEMORY;
    hdma_usart3_rx.Init.PeriphInc = DMA_PINC_DISABLE;
    hdma_usart3_rx.Init.MemInc = DMA_MINC_ENABLE;
    hdma_usart3_rx.Init.PeriphDataAlignment = DMA_PDATAALIGN_BYTE;
    hdma_usart3_rx.Init.MemDataAlignment = DMA_MDATAALIGN_BYTE;
    hdma_usart3_rx.Init.Mode = DMA_CIRCULAR;
    hdma_usart3_rx.Init.Priority = DMA_PRIORITY_LOW;
    if (HAL_DMA_Init(&hdma_usart3_rx) != HAL_OK)
    {
      Error_Handler();
    }

    __HAL_LINKDMA(uartHandle,hdmarx,hdma_usart3_rx);

    /* USART3 interrupt Init */
    HAL_NVIC_SetPriority(USART3_IRQn, 0, 0);
    HAL_NVIC_EnableIRQ(USART3_IRQn);
  /* USER CODE BEGIN USART3_MspInit 1 */

  /* USER CODE END USART3_MspInit 1 */
  }
}

void HAL_UART_MspDeInit(UART_HandleTypeDef* uartHandle)
{

  if(uartHandle->Instance==USART1)
  {
  /* USER CODE BEGIN USART1_MspDeInit 0 */

  /* USER CODE END USART1_MspDeInit 0 */
    /* Peripheral clock disable */
    __HAL_RCC_USART1_CLK_DISABLE();

    /**USART1 GPIO Configuration
    PA9     ------> USART1_TX
    PA10     ------> USART1_RX
    */
    HAL_GPIO_DeInit(GPIOA, GPIO_PIN_9|GPIO_PIN_10);

    /* USART1 DMA DeInit */
    HAL_DMA_DeInit(uartHandle->hdmarx);

    /* USART1 interrupt Deinit */
    HAL_NVIC_DisableIRQ(USART1_IRQn);
  /* USER CODE BEGIN USART1_MspDeInit 1 */

  /* USER CODE END USART1_MspDeInit 1 */
  }
  else if(uartHandle->Instance==USART3)
  {
  /* USER CODE BEGIN USART3_MspDeInit 0 */

  /* USER CODE END USART3_MspDeInit 0 */
    /* Peripheral clock disable */
    __HAL_RCC_USART3_CLK_DISABLE();

    /**USART3 GPIO Configuration
    PB10     ------> USART3_TX
    PB11     ------> USART3_RX
    */
    HAL_GPIO_DeInit(GPIOB, GPIO_PIN_10|GPIO_PIN_11);

    /* USART3 DMA DeInit */
    HAL_DMA_DeInit(uartHandle->hdmarx);

    /* USART3 interrupt Deinit */
    HAL_NVIC_DisableIRQ(USART3_IRQn);
  /* USER CODE BEGIN USART3_MspDeInit 1 */

  /* USER CODE END USART3_MspDeInit 1 */
  }
}

/* USER CODE BEGIN 1 */
void HAL_UART_RxCpltCallback(UART_HandleTypeDef *huart)
{
#if 0
    if(huart->Instance == USART3)
    {
        if((g_usart_rx_sta & 0x8000) == 0)
        {
            if(g_usart_rx_sta & 0x4000)
            {
                if(g_rx_buffer[0] != 0x0a) 
                {
                    g_usart_rx_sta = 0;
                }
                else 
                {
                    g_usart_rx_sta |= 0x8000;
                }
            }
            else
            {
                if(g_rx_buffer[0] == 0x0d)
                {
                    g_usart_rx_sta |= 0x4000;
                }
                else
                {
                    g_usart_rx_buf[g_usart_rx_sta & 0X3FFF] = g_rx_buffer[0] ;
                    g_usart_rx_sta++;
                    if(g_usart_rx_sta > (USART_REC_LEN - 1))
                    {
                        g_usart_rx_sta = 0;
                    }
                }
            }
        }
        
        HAL_UART_Receive_IT(&huart3, (uint8_t *)g_rx_buffer, RXBUFFERSIZE);
    }
#endif
    (void)huart;
}
uint8_t CalculateChecksum(const uint8_t *data, uint8_t len) {
    uint8_t checksum = 0;
    for (uint8_t i = 0; i < len; i++) {
        checksum += data[i];
    }
    return checksum;
}
uint8_t CalculateChecksum1(const uint8_t *data, uint8_t len) {
    uint8_t checksum = 0;
    for (uint8_t i = 0; i < len; i++) {
        checksum += data[i];
    }
    return checksum;
}

/*
 * Parse a received binary frame from the BLE module (USART3).
 *
 * Frame format: [0xAA][0x55][Cmd][PayloadLen][Payload...][Checksum][0x0D][0x0A]
 *   - Sync  : 0xAA 0x55 (2 bytes)
 *   - Cmd   : Sweep_Mode_Type opcode (1 byte)
 *   - Len   : Payload length in bytes (1 byte, must be even)
 *   - Payload : Command-specific parameters (Len bytes)
 *   - Checksum: Sum of Payload bytes, modulo 256 (1 byte)
 *   - Term  : 0x0D 0x0A CRLF (2 bytes)
 *
 * On success the parsed Sweep_Mode and payload[] globals are updated.
 */
void ProcessReceivedData(uint8_t *data, uint16_t len) {
    if (len < 7) {
        return;
    }

    /* Validate frame sync bytes and CRLF terminator */
    if (data[0] != 0xAA || data[1] != 0x55 || data[len - 2] != 0x0D || data[len - 1] != 0x0A) {
        return;
    }

    const uint8_t payload_len = data[3];
    if (((uint16_t)payload_len + 7u) != len) {
        return;
    }

    if (payload_len > sizeof(payload)) {
        return;
    }

    /* Payload must be a whole number of 16-bit words */
    if ((payload_len & 0x01u) != 0u) {
        return;
    }

    /* Verify checksum over the payload bytes */
    const uint8_t checksum = data[4u + payload_len];
    const uint8_t calculated = CalculateChecksum(&data[4], payload_len);
    if (calculated != checksum) {
        return;
    }

    /* Validate command opcode and expected payload length */
    const Sweep_Mode_Type cmd = (Sweep_Mode_Type)data[2];
    const uint8_t expected_payload_len = GetExpectedPayloadLength(cmd);
    if (expected_payload_len == 0xFFu || payload_len != expected_payload_len) {
        return;
    }

    /* Copy payload to global buffer and set the sweep mode */
    memset(payload, 0, sizeof(payload));
    if (payload_len > 0u) {
        memcpy(payload, &data[4], payload_len);
    }
    Sweep_Mode = cmd;
}

/*
 * Send a binary response frame back to the host over the BLE UART (USART3).
 *
 * Format: [0xAA][0x55][Cmd][PayloadLen][Payload...][Checksum][0x0D][0x0A]
 * Used for measurement data streaming during experiments.
 */
void SendData(uint8_t cmd, uint8_t *data, uint8_t len) {
    uint8_t tx_buffer[256];
    uint8_t checksum = CalculateChecksum(data, len);

    tx_buffer[0] = 0xAA;
    tx_buffer[1] = 0x55;
    tx_buffer[2] = cmd;
    tx_buffer[3] = len;
    memcpy(&tx_buffer[4], data, len);
    tx_buffer[4 + len] = checksum;
    tx_buffer[5 + len] = 0x0D;
    tx_buffer[6 + len] = 0x0A;

    // HAL_UART_Transmit(&huart1, tx_buffer, 7 + len, HAL_MAX_DELAY);  // USART1 disabled
    HAL_UART_Transmit(&huart3, tx_buffer, 7 + len, HAL_MAX_DELAY);
}
/*
 * Identical frame format to SendData, using a separate checksum
 * function for legacy compatibility with the Android host.
 */
void SendData1(uint8_t cmd, uint8_t *data, uint8_t len) {
    uint8_t tx_buffer[256];
    uint8_t checksum = CalculateChecksum1(data, len);

    tx_buffer[0] = 0xAA;
    tx_buffer[1] = 0x55;
    tx_buffer[2] = cmd;
    tx_buffer[3] = len;
    memcpy(&tx_buffer[4], data, len);
    tx_buffer[4 + len] = checksum;
    tx_buffer[5 + len] = 0x0D;
    tx_buffer[6 + len] = 0x0A;

    // HAL_UART_Transmit(&huart1, tx_buffer, 7 + len, HAL_MAX_DELAY);  // USART1 disabled
    HAL_UART_Transmit(&huart3, tx_buffer, 7 + len, HAL_MAX_DELAY);
}

/* Send a raw AT command to the BLE module via USART3 and wait for a response. */
void Send_AT_Command(char *cmd) {
    HAL_UART_Transmit(&huart3, (uint8_t *)cmd, strlen(cmd), HAL_MAX_DELAY);
    HAL_UART_Transmit(&huart3, (uint8_t *)"\r\n", 2, HAL_MAX_DELAY);
    HAL_Delay(200);
}

/* Initialise the BLE module to default transparent UART mode. */
void Configure_BLE_Module(void) {
    Send_AT_Command("AT+ENAT");   /* Enable AT mode */
    Send_AT_Command("AT+RDEF");   /* Restore factory defaults */
    HAL_Delay(1000);
}
#endif




/* USER CODE END 1 */
