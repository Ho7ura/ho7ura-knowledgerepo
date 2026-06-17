#include "ecw.h"
#include "usart.h"
#include "delay.h"
#include "adc.h"
#include "tim.h" 
#include "iwdg.h"

// ADG704 四档采样电阻 (Ω)
#define SAMPLE_RESISTOR_1    1000.0f    // RANGE_1: 1kΩ
#define SAMPLE_RESISTOR_2   10000.0f    // RANGE_2: 10kΩ
#define SAMPLE_RESISTOR_3  100000.0f    // RANGE_3: 100kΩ
#define SAMPLE_RESISTOR_4 1000000.0f    // RANGE_4: 1MΩ

// 各量程零点偏移 (μA)，需实际校准
#define ZERO_OFFSET_1  0.0f
#define ZERO_OFFSET_2  0.0f
#define ZERO_OFFSET_3  0.0f
#define ZERO_OFFSET_4  0.0f

#define UA_CONVERT_COEFF     1000000.0f
#define MA_CONVERT_COEFF     1000.0f
#define NA_CONVERT_COEFF     1000000000.0f
#define POTENTIOSTAT_VOLTAGE_LIMIT_MV 1650

/* USER CODE BEGIN PV */
#define ADC_RESOLUTION 4095
#define DAC_RESOLUTION 4095
#define CURRENT_SENSE_RESISTOR 1.0

extern ADC_HandleTypeDef hadc1;
extern DAC_HandleTypeDef hdac;

Sweep_Mode_Type Sweep_Mode = DORMANT;
Current_Range_Type Current_Range = RANGE_2;

unsigned long lastTime = 0;

IT_Params itParams;
CV_Params cvParams;
DPV_Params dpvParams;
SWV_Params swvParams;

float volts = 0.0f;
float time = 0.0f;
float current = 0.0f;
#include "main.h"

volatile uint8_t samplingFlag = 0;

static bool isVoltageWithinOutputRange(int16_t voltage) {
    return voltage >= -POTENTIOSTAT_VOLTAGE_LIMIT_MV &&
           voltage <= POTENTIOSTAT_VOLTAGE_LIMIT_MV;
}

static void sendDormantResponse(void) {
    Sweep_Mode = DORMANT;
    uint8_t emptyData[1] = {0};
    SendData(DORMANT, emptyData, 1);
}

void InitParams() {

    itParams.initialPotential = 0;
    itParams.runTime = 0;
    itParams.samplingInterval = 100;
    itParams.restingTime = 0;
    itParams.sensitivity = 1;

    swvParams.Gain = 1;
    swvParams.startV = -500;
    swvParams.endV = 500;
    swvParams.pulseAmp = 50;
    swvParams.stepV = 10;
    swvParams.freq = 10.0;
    swvParams.setToZero = 1;

    setCurrentRange(RANGE_2);
}

void runIT(int Gain, int voltage, uint32_t duration, int samplingInterval, int restTime) {
    Current_Range_Type range = (Current_Range_Type)Gain;
    setCurrentRange(range);

    if (!isVoltageWithinOutputRange((int16_t)voltage) ||
        duration == 0 ||
        samplingInterval <= 0) {
        sendDormantResponse();
        return;
    }

    uint32_t startTime = HAL_GetTick();
    uint32_t currentTime;
    uint32_t pauseStartTime = 0;
    uint32_t totalPauseDuration = 0;
    uint16_t dataSequence = 1;

    __HAL_TIM_SET_AUTORELOAD(&htim2, (samplingInterval * 10) - 1);
    __HAL_TIM_SET_COUNTER(&htim2, 0);
    HAL_TIM_Base_Start_IT(&htim2);

	setPotentiostatVoltage(voltage);

    while (1) {
        currentTime = HAL_GetTick();

        if (Sweep_Mode == DORMANT) {
            break;
        } else if (Sweep_Mode == PAUSE) {
            if (pauseStartTime == 0) {
                pauseStartTime = currentTime;
            }
            IWDG_Feed();
        } else if (Sweep_Mode == IT) {
            if (pauseStartTime != 0) {
                totalPauseDuration += currentTime - pauseStartTime;
                pauseStartTime = 0;
            }

            if ((currentTime - startTime - totalPauseDuration) >= (uint32_t)duration * 1000) {
                HAL_DAC_Stop(&hdac, DAC_CHANNEL_1);
                Sweep_Mode = DORMANT;
                break;
            }

            if (samplingFlag) {
                samplingFlag = 0;
                float current = sampleCurrent(ADC_CHANNEL_7);

                int16_t currentInt = (int16_t)current;
                int16_t currentFrac = (int16_t)((current - currentInt) * 1000);
                int8_t currentHigh = (int8_t)(currentInt >> 8);
                int8_t currentLow = (int8_t)(currentInt & 0xFF);

                int8_t fracHigh = (int8_t)(currentFrac >> 8);
                int8_t fracLow = (int8_t)(currentFrac & 0xFF);

                uint8_t dataBuffer[6];
                dataBuffer[0] = (uint8_t)(dataSequence >> 8);
                dataBuffer[1] = (uint8_t)(dataSequence & 0xFF);
                dataBuffer[2] = currentHigh;
                dataBuffer[3] = currentLow;
                dataBuffer[4] = fracHigh;
                dataBuffer[5] = fracLow;

                SendData(0x0D, dataBuffer, 6);
                dataSequence++;
            }
            IWDG_Feed();
        }
    }

    HAL_TIM_Base_Stop_IT(&htim2);
    sendDormantResponse();
}


void runCV(uint8_t Gain, uint8_t cycles, int16_t startV,
           int16_t endV, int16_t vertex1, int16_t vertex2,
           int16_t stepV, uint16_t rate, bool setToZero) {
    Current_Range_Type range = (Current_Range_Type)Gain;
    setCurrentRange(range);

    if (cycles == 0 || rate == 0) {
        sendDormantResponse();
        return;
    }

    if (!isVoltageWithinOutputRange(startV) ||
        !isVoltageWithinOutputRange(endV) ||
        !isVoltageWithinOutputRange(vertex1) ||
        !isVoltageWithinOutputRange(vertex2)) {
        sendDormantResponse();
        return;
    }

    stepV = (stepV < 0) ? -stepV : stepV;
    if (stepV == 0) {
        sendDormantResponse();
        return;
    }

    if (vertex1 > startV) {
        runCVForward(cycles, startV, endV, vertex1, vertex2, stepV, rate);
    } else {
        runCVBackward(cycles, startV, endV, vertex1, vertex2, stepV, rate);
    }

    if (setToZero) {
        setPotentiostatVoltage(0);
    }
    sendDormantResponse();
}

void reset_Voltammogram_arrays(void) {
    volts = 0.0f;
    time = 0.0f;
    current = 0.0f;
}

void runCVForward(uint8_t cycles, int16_t startV, int16_t endV,
                 int16_t vertex1, int16_t vertex2, int16_t stepV, uint16_t rate) {

    int16_t outputVoltage = startV;

    for (uint8_t i = 0; i < cycles; i++) {
        for (outputVoltage = startV; outputVoltage <= vertex1; outputVoltage += stepV) {
            sampleAndSendData(CV, outputVoltage, rate);

            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
            IWDG_Feed();
        }

        for (outputVoltage = vertex1 - stepV; outputVoltage >= vertex2; outputVoltage -= stepV) {
            sampleAndSendData(CV, outputVoltage, rate);

            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
            IWDG_Feed();
        }

        for (outputVoltage = vertex2 + stepV; outputVoltage <= endV; outputVoltage += stepV) {
            sampleAndSendData(CV, outputVoltage, rate);

            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
            IWDG_Feed();
        }

        IWDG_Feed();
    }
}

void runCVBackward(uint8_t cycles, int16_t startV, int16_t endV,
                  int16_t vertex1, int16_t vertex2, int16_t stepV, uint16_t rate)
{
    int16_t outputVoltage = startV;

    for (uint8_t i = 0; i < cycles; i++)
    {
        for (outputVoltage = startV; outputVoltage >= vertex1; outputVoltage -= stepV)
        {
            sampleAndSendData(CV, outputVoltage, rate);

            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
            IWDG_Feed();
        }

        for (outputVoltage = vertex1 + stepV; outputVoltage <= vertex2; outputVoltage += stepV)
        {
            sampleAndSendData(CV, outputVoltage, rate);

            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
            IWDG_Feed();
        }

        for (outputVoltage = vertex2 - stepV; outputVoltage >= endV; outputVoltage -= stepV)
        {
            sampleAndSendData(CV, outputVoltage, rate);

            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
            IWDG_Feed();
        }

        IWDG_Feed();
    }
}

void RunSweepMode(Sweep_Mode_Type Sweep_Mode) {
    switch (Sweep_Mode) {
        case IT:
            HAL_GPIO_WritePin(GPIOA, GPIO_PIN_8, GPIO_PIN_SET);
            runIT(itParams.sensitivity, itParams.initialPotential, itParams.runTime,
                  itParams.samplingInterval, itParams.restingTime);
            break;

        case CV:
            HAL_GPIO_WritePin(GPIOA, GPIO_PIN_8, GPIO_PIN_SET);
            runCV(cvParams.Gain, cvParams.cycles, cvParams.startV, cvParams.endV,
                  cvParams.vertex1, cvParams.vertex2, cvParams.stepV, cvParams.rate,
                  cvParams.setToZero);
            break;

        case DPV:
            HAL_GPIO_WritePin(GPIOA, GPIO_PIN_8, GPIO_PIN_SET);
            runDPV(dpvParams.Gain, dpvParams.startV, dpvParams.endV,
                   dpvParams.pulseAmp, dpvParams.stepV, dpvParams.pulse_width,
                   dpvParams.pulse_period, dpvParams.quietTime, dpvParams.range,
                   dpvParams.setToZero);
            break;

        case SWV:
            HAL_GPIO_WritePin(GPIOA, GPIO_PIN_8, GPIO_PIN_SET);
            runSWV(swvParams.Gain, swvParams.startV, swvParams.endV,
                   swvParams.pulseAmp, swvParams.stepV, swvParams.freq,
                   swvParams.setToZero);
            break;

        default:
            break;
    }
}

void parseSweepModePayload(Sweep_Mode_Type Sweep_Mode, const int16_t* payload) {
    switch (Sweep_Mode) {
        case IT:
            itParams.sensitivity = payload[0];
            itParams.initialPotential = payload[1];
            itParams.runTime = ((uint32_t)payload[2] << 16) | (uint16_t)payload[3];
            itParams.samplingInterval = payload[4];
            itParams.restingTime = payload[5];

            break;

        case CV:
            cvParams.Gain = payload[0];
            cvParams.cycles = payload[1];
            cvParams.startV = payload[2];
            cvParams.endV = payload[3];
            cvParams.vertex1 = payload[4];
            cvParams.vertex2 = payload[5];
            cvParams.stepV = payload[6];
            cvParams.rate = payload[7];
            cvParams.setToZero = payload[8];
            break;

        case DPV:
            dpvParams.Gain = payload[0];
            dpvParams.startV = payload[1];
            dpvParams.endV = payload[2];
            dpvParams.pulseAmp = payload[3];
            dpvParams.stepV = payload[4];
            dpvParams.pulse_width = payload[5];
            dpvParams.pulse_period = payload[6];
            dpvParams.quietTime = payload[7];
            dpvParams.range = payload[8];
            dpvParams.setToZero = payload[9];
            break;

        case SWV:
            swvParams.Gain = payload[0];
            swvParams.startV = payload[1];
            swvParams.endV = payload[2];
            swvParams.pulseAmp = payload[3];
            swvParams.stepV = payload[4];
            union {
                float f;
                int16_t i[2];
            } freq_union;
            freq_union.i[0] = payload[5];
            freq_union.i[1] = payload[6];
            swvParams.freq = freq_union.f;
            swvParams.setToZero = payload[7];
            break;

        case CTLPANEL:
            if (payload[0] == 0x01) {
                uint8_t rangeCode = (uint8_t)payload[1];
                if (rangeCode <= RANGE_4) {
                    setCurrentRange((Current_Range_Type)rangeCode);
                }
            }
            break;

        default:
            break;
    }
}

uint16_t setPotentiostatVoltage(int16_t voltage) {
    if (voltage < -POTENTIOSTAT_VOLTAGE_LIMIT_MV) {
        voltage = -POTENTIOSTAT_VOLTAGE_LIMIT_MV;
    } else if (voltage > POTENTIOSTAT_VOLTAGE_LIMIT_MV) {
        voltage = POTENTIOSTAT_VOLTAGE_LIMIT_MV;
    }

    float dacVoltage = (4095.0f * ((float)POTENTIOSTAT_VOLTAGE_LIMIT_MV - voltage) /
                        (2.0f * POTENTIOSTAT_VOLTAGE_LIMIT_MV));

    if (dacVoltage < 0.0f) {
        dacVoltage = 0.0f;
    } else if (dacVoltage > 4095.0f) {
        dacVoltage = 4095.0f;
    }

    uint16_t dacValue = (uint16_t)dacVoltage;
    HAL_DAC_SetValue(&hdac, DAC_CHANNEL_1, DAC_ALIGN_12B_R, dacValue);
    HAL_DAC_Start(&hdac, DAC_CHANNEL_1);

    return dacValue;
}

void setCurrentRange(Current_Range_Type range) {
    if (Current_Range == range) return;
    Current_Range = range;

    // ADG704 真值表: A1(PA2) A0(PA1) EN(PB12)
    HAL_GPIO_WritePin(ADG704_EN_PORT, ADG704_EN_PIN, GPIO_PIN_RESET);  // 先禁用
    switch (range) {
        case RANGE_1:
            HAL_GPIO_WritePin(ADG704_A0_PORT, ADG704_A0_PIN, GPIO_PIN_RESET);
            HAL_GPIO_WritePin(ADG704_A1_PORT, ADG704_A1_PIN, GPIO_PIN_RESET);
            break;
        case RANGE_2:
            HAL_GPIO_WritePin(ADG704_A0_PORT, ADG704_A0_PIN, GPIO_PIN_SET);
            HAL_GPIO_WritePin(ADG704_A1_PORT, ADG704_A1_PIN, GPIO_PIN_RESET);
            break;
        case RANGE_3:
            HAL_GPIO_WritePin(ADG704_A0_PORT, ADG704_A0_PIN, GPIO_PIN_RESET);
            HAL_GPIO_WritePin(ADG704_A1_PORT, ADG704_A1_PIN, GPIO_PIN_SET);
            break;
        case RANGE_4:
            HAL_GPIO_WritePin(ADG704_A0_PORT, ADG704_A0_PIN, GPIO_PIN_SET);
            HAL_GPIO_WritePin(ADG704_A1_PORT, ADG704_A1_PIN, GPIO_PIN_SET);
            break;
    }
    HAL_GPIO_WritePin(ADG704_EN_PORT, ADG704_EN_PIN, GPIO_PIN_SET);    // 使能
    HAL_Delay(1);
}

Current_Range_Type getCurrentRange(void) {
    return Current_Range;
}

float getSampleResistor(Current_Range_Type range) {
    switch (range) {
        case RANGE_1: return SAMPLE_RESISTOR_1;
        case RANGE_2: return SAMPLE_RESISTOR_2;
        case RANGE_3: return SAMPLE_RESISTOR_3;
        case RANGE_4: return SAMPLE_RESISTOR_4;
        default:      return SAMPLE_RESISTOR_2;
    }
}

inline float sampleCurrent(uint8_t adcChannel) {
    uint16_t adcValue = Get_Adc_Average(adcChannel, 10);
    float voltage = (float)adcValue * 3.3f / 4095.0f;
    float sampleResistor = getSampleResistor(Current_Range);
    float current = ((1.65f - voltage) / sampleResistor) * UA_CONVERT_COEFF;

    switch (Current_Range) {
        case RANGE_1: current -= ZERO_OFFSET_1; break;
        case RANGE_2: current -= ZERO_OFFSET_2; break;
        case RANGE_3: current -= ZERO_OFFSET_3; break;
        case RANGE_4: current -= ZERO_OFFSET_4; break;
    }

    return current;
}

float readREPotential(void) {
    uint16_t adcValue = Get_Adc_Average(ADC_CHANNEL_6, 10);
    return (float)adcValue * 3300.0f / 4095.0f;       // 返回 RE 引脚电压，单位 mV
}

void packAndSendData(Sweep_Mode_Type mode, int16_t voltage, float current) {
    int8_t voltageHigh = (int8_t)(voltage >> 8);
    int8_t voltageLow = (int8_t)(voltage & 0xFF);

    int16_t currentInt = (int16_t)current;
    int16_t currentFrac = (int16_t)((current - currentInt) * 1000);
    int8_t currentHigh = (int8_t)(currentInt >> 8);
    int8_t currentLow = (int8_t)(currentInt & 0xFF);

    int8_t fracHigh = (int8_t)(currentFrac >> 8);
    int8_t fracLow = (int8_t)(currentFrac & 0xFF);

    uint8_t dataBuffer[6] = {
        (uint8_t)voltageHigh,
        (uint8_t)voltageLow,
        (uint8_t)currentHigh,
        (uint8_t)currentLow,
        (uint8_t)fracHigh,
        (uint8_t)fracLow
    };
    SendData1(mode, dataBuffer, 6);
}

void sampleAndSendData(Sweep_Mode_Type mode,int16_t voltage, uint16_t rate) {
    setPotentiostatVoltage(voltage);
    lastTime= HAL_GetTick();
    while (HAL_GetTick() - lastTime < rate)
        ;
    current = sampleCurrent(ADC_CHANNEL_7);
    packAndSendData(mode, voltage, current);
    lastTime = HAL_GetTick();
}

void runDPV(uint8_t Gain, int16_t startV, int16_t endV,
            int16_t pulseAmp, int16_t stepV, uint32_t pulse_width, uint32_t pulse_period,
            uint32_t quietTime, uint8_t range, bool setToZero)
{
    Current_Range_Type currentRange = (Current_Range_Type)Gain;
    setCurrentRange(currentRange);

    if (!isVoltageWithinOutputRange(startV) ||
        !isVoltageWithinOutputRange(endV) ||
        pulse_width == 0 ||
        pulse_period == 0) {
        sendDormantResponse();
        return;
    }

    reset_Voltammogram_arrays();

    if (pulse_width > pulse_period)
    {
        uint32_t temp = pulse_width;
        pulse_width = pulse_period;
        pulse_period = temp;
    }

    pulseAmp = (pulseAmp < 0) ? -pulseAmp : pulseAmp;
    stepV = (stepV < 0) ? -stepV : stepV;
    if (stepV == 0) {
        sendDormantResponse();
        return;
    }
    uint32_t off_time = pulse_period - pulse_width;

    // 设置起始电压并等待静置时间
    setPotentiostatVoltage(startV);
    unsigned long time1 = HAL_GetTick();
    while (HAL_GetTick() - time1 < quietTime * 1000)
        IWDG_Feed();

    if (startV < endV)
    {
        runDPVForward(startV, endV, pulseAmp, stepV, pulse_width, off_time);
    }
    else
    {
        runDPVBackward(startV, endV, pulseAmp, stepV, pulse_width, off_time);
    }

    if (setToZero)
    {
        setPotentiostatVoltage(0);
    }
    sendDormantResponse();
}

void runDPVForward(int16_t startV, int16_t endV, int16_t pulseAmp,
                   int16_t stepV, uint32_t pulse_width, uint32_t off_time)
{
    float i_forward = 0;
    float i_backward = 0;

    for (int16_t outputVoltage = startV; outputVoltage <= endV; outputVoltage += stepV)
    {
        if (Sweep_Mode == DORMANT)
        {
            setPotentiostatVoltage(0);
            Sweep_Mode = DORMANT;
            return;
        }

        if (outputVoltage != startV) {
            setPotentiostatVoltage(outputVoltage);
        }
        lastTime = HAL_GetTick();
        uint32_t baselineWaitTime = (off_time > 10) ? (off_time - 5) : off_time;
        while (HAL_GetTick() - lastTime < baselineWaitTime)
        {
            if (Sweep_Mode == PAUSE)
            {
                while (Sweep_Mode == PAUSE)
                {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT)
                    {
                        setPotentiostatVoltage(0);
                        Sweep_Mode = DORMANT;
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT)
            {
                setPotentiostatVoltage(0);
                Sweep_Mode = DORMANT;
                return;
            }
        }

        i_backward = sampleCurrent(ADC_CHANNEL_7);

        setPotentiostatVoltage(outputVoltage+pulseAmp);
        lastTime = HAL_GetTick();
        while (HAL_GetTick() - lastTime < pulse_width)
        {
            if (Sweep_Mode == PAUSE)
            {
                while (Sweep_Mode == PAUSE)
                {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT)
                    {
                        setPotentiostatVoltage(0);
                        Sweep_Mode = DORMANT;
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT)
            {
                setPotentiostatVoltage(0);
                Sweep_Mode = DORMANT;
                return;
            }
        }

        i_forward = sampleCurrent(ADC_CHANNEL_7);

        packAndSendData(DPV, outputVoltage, i_forward - i_backward);

        IWDG_Feed();
    }
}

void runDPVBackward(int16_t startV, int16_t endV, int16_t pulseAmp,
                    int16_t stepV, uint32_t pulse_width, uint32_t off_time)
{
    float i_forward = 0;
    float i_backward = 0;

    for (int16_t outputVoltage = startV; outputVoltage >= endV; outputVoltage -= stepV)
    {
        if (Sweep_Mode == DORMANT)
        {
            setPotentiostatVoltage(0);
            Sweep_Mode = DORMANT;
            return;
        }

        if (outputVoltage != startV) {
            setPotentiostatVoltage(outputVoltage);
        }
        lastTime = HAL_GetTick();
        uint32_t baselineWaitTime = (off_time > 10) ? (off_time - 5) : off_time;
        while (HAL_GetTick() - lastTime < baselineWaitTime)
        {
            if (Sweep_Mode == PAUSE)
            {
                while (Sweep_Mode == PAUSE)
                {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT)
                    {
                        setPotentiostatVoltage(0);
                        Sweep_Mode = DORMANT;
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT)
            {
                setPotentiostatVoltage(0);
                Sweep_Mode = DORMANT;
                return;
            }
        }

        i_backward = sampleCurrent(ADC_CHANNEL_7);

        setPotentiostatVoltage(outputVoltage+pulseAmp);
        lastTime = HAL_GetTick();
        while (HAL_GetTick() - lastTime < pulse_width)
        {
            if (Sweep_Mode == PAUSE)
            {
                while (Sweep_Mode == PAUSE)
                {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT)
                    {
                        setPotentiostatVoltage(0);
                        Sweep_Mode = DORMANT;
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT)
            {
                setPotentiostatVoltage(0);
                Sweep_Mode = DORMANT;
                return;
            }
        }

        i_forward = sampleCurrent(ADC_CHANNEL_7);

        packAndSendData(DPV, outputVoltage, i_forward - i_backward);

        IWDG_Feed();
    }
}

void runSWVForward(int16_t startV, int16_t endV, int16_t pulseAmp,
               int16_t stepV, float freq)
{
    float i_forward = 0;
    float i_reverse = 0;

    for (int16_t outputVoltage = startV; outputVoltage <= endV; outputVoltage += stepV)
    {
        if (Sweep_Mode == PAUSE) {
            while (Sweep_Mode == PAUSE) {
                IWDG_Feed();
                if (Sweep_Mode == DORMANT) {
                    setPotentiostatVoltage(0);
                    return;
                }
            }
        }
        else if (Sweep_Mode == DORMANT) {
            setPotentiostatVoltage(0);
            return;
        }

        setPotentiostatVoltage(outputVoltage + pulseAmp);
        lastTime = HAL_GetTick();
        while (HAL_GetTick() - lastTime < freq) {
            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
        }
        i_forward = sampleCurrent(ADC_CHANNEL_7);

        setPotentiostatVoltage(outputVoltage - pulseAmp);
        lastTime = HAL_GetTick();
        while (HAL_GetTick() - lastTime < freq) {
            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
        }
        i_reverse = sampleCurrent(ADC_CHANNEL_7);

        packAndSendData(SWV, outputVoltage, i_forward - i_reverse);
        IWDG_Feed();
    }
}

void runSWVBackward(int16_t startV, int16_t endV, int16_t pulseAmp,
                        int16_t stepV, float freq)
{
    float i_forward = 0;
    float i_reverse = 0;

    for (int16_t outputVoltage = startV; outputVoltage >= endV; outputVoltage -= stepV)
    {
        if (Sweep_Mode == PAUSE) {
            while (Sweep_Mode == PAUSE) {
                IWDG_Feed();
                if (Sweep_Mode == DORMANT) {
                    setPotentiostatVoltage(0);
                    return;
                }
            }
        }
        else if (Sweep_Mode == DORMANT) {
            setPotentiostatVoltage(0);
            return;
        }

        setPotentiostatVoltage(outputVoltage + pulseAmp);
        lastTime = HAL_GetTick();
        while (HAL_GetTick() - lastTime < freq) {
            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
        }
        i_forward = sampleCurrent(ADC_CHANNEL_7);

        setPotentiostatVoltage(outputVoltage - pulseAmp);
        lastTime = HAL_GetTick();
        while (HAL_GetTick() - lastTime < freq) {
            if (Sweep_Mode == PAUSE) {
                while (Sweep_Mode == PAUSE) {
                    IWDG_Feed();
                    if (Sweep_Mode == DORMANT) {
                        setPotentiostatVoltage(0);
                        return;
                    }
                }
            }
            else if (Sweep_Mode == DORMANT) {
                setPotentiostatVoltage(0);
                return;
            }
        }
        i_reverse = sampleCurrent(ADC_CHANNEL_7);

        packAndSendData(SWV, outputVoltage, i_forward - i_reverse);
        IWDG_Feed();
    }
}

void runSWV(uint8_t Gain, int16_t startV, int16_t endV,
            int16_t pulseAmp, int16_t stepV, float freq, bool setToZero)
{
  Current_Range_Type range = (Current_Range_Type)Gain;
  setCurrentRange(range);

  if (!isVoltageWithinOutputRange(startV) ||
      !isVoltageWithinOutputRange(endV) ||
      freq <= 0.0f) {
      sendDormantResponse();
      return;
  }

  stepV = (stepV < 0) ? -stepV : stepV;
  pulseAmp = (pulseAmp < 0) ? -pulseAmp : pulseAmp;
  if (stepV == 0) {
      sendDormantResponse();
      return;
  }

  float halfPeriodMs = 1000.0f / (2.0f * freq);
  if (halfPeriodMs < 1.0f) {
      halfPeriodMs = 1.0f;
  }
  freq = (float)((uint16_t)halfPeriodMs - 1u);

  reset_Voltammogram_arrays();

  // 设置起始电压并等待稳定
  setPotentiostatVoltage(startV);
  HAL_Delay(10);

  if (startV < endV) {
    runSWVForward(startV, endV, pulseAmp, stepV, freq);
  } else {
    runSWVBackward(startV, endV, pulseAmp, stepV, freq);
  }

  if (setToZero) {
    setPotentiostatVoltage(0);
  }

  sendDormantResponse();
}
