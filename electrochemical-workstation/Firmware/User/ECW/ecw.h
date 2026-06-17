#ifndef ECW_H
#define ECW_H

#include <stdint.h>
#include <stdbool.h>

#include <main.h>

#define LED_PIN 26

/* ADG704BRMZ 4:1 analog multiplexer control pins for TIA range selection. */
#define ADG704_A0_PORT   GPIOA
#define ADG704_A0_PIN    GPIO_PIN_1
#define ADG704_A1_PORT   GPIOA
#define ADG704_A1_PIN    GPIO_PIN_2
#define ADG704_EN_PORT   GPIOB
#define ADG704_EN_PIN    GPIO_PIN_12

/*
 * TIA feedback resistor ranges via ADG704 analog mux.
 * Lower resistance = higher current before saturation.
 */
typedef enum {
    RANGE_1 = 0,  /* 1kΩ — mA-scale currents (e.g. high-concentration redox) */
    RANGE_2 = 1,  /* 10kΩ — µA-scale (default, covers most electrochemistry) */
    RANGE_3 = 2,  /* 100kΩ — tens-of-nA to low-µA (trace detection) */
    RANGE_4 = 3   /* 1MΩ — nA-scale (high-sensitivity / low-concentration) */
} Current_Range_Type;

/*
 * Sweep method opcodes match the host protocol's command byte.
 * The main loop dispatches to the matching runXxx() via this value.
 */
typedef enum {
    DORMANT = 0x00,  /* Idle — no experiment running */
    DPV    = 0x06,   /* Differential Pulse Voltammetry */
    SWV    = 0x09,   /* Square Wave Voltammetry */
    IT     = 0x0D,   /* Chronoamperometry (i-t curve) */
    CTLPANEL = 0x21, /* Control-panel command (set range, etc.) */
    CV     = 0x23,   /* Cyclic Voltammetry */
    PAUSE  = 0xFF,   /* Pause the running experiment (host-requested) */
} Sweep_Mode_Type;

/* CV parameters — triangular potential sweep between vertex potentials. */
typedef struct {
    uint8_t Gain;       /* Current range selector (maps to Current_Range_Type) */
    uint8_t cycles;     /* Number of full CV cycles */
    int16_t startV;     /* Starting potential (mV) */
    int16_t endV;       /* Ending / final potential (mV) */
    int16_t vertex1;    /* First switching potential (mV) */
    int16_t vertex2;    /* Second switching potential (mV, reverse scan) */
    int16_t stepV;      /* Potential increment per step (mV) */
    uint16_t rate;      /* Scan rate in ms per step (not mV/s) */
    bool setToZero;     /* Return WE to 0 mV after sweep completes */
} CV_Params;

/* DPV parameters — staircase with synchronized current sampling pre/post pulse. */
typedef struct {
    uint8_t Gain;
    int16_t startV;
    int16_t endV;
    int16_t pulseAmp;       /* Pulse amplitude (mV) */
    int16_t stepV;          /* Step increment (mV) */
    uint32_t pulse_width;   /* Pulse duration (ms) */
    uint32_t pulse_period;  /* Period between pulse starts (ms) */
    uint32_t quietTime;     /* Equilibration time before sweep (s) */
    uint8_t range;          /* Reserved / alternative range field */
    bool setToZero;
} DPV_Params;

/* IT (chronoamperometry) params — constant potential, current vs. time. */
typedef struct {
    int16_t initialPotential;   /* Applied potential (mV) */
    uint32_t runTime;           /* Total experiment duration (s) */
    int16_t samplingInterval;   /* Interval between data points (ms / 10 via TIM2) */
    int16_t restingTime;        /* Rest period before start (s, unused) */
    int16_t sensitivity;        /* Current range selector */
} IT_Params;

/* SWV parameters — square-wave modulation superimposed on a staircase ramp. */
typedef struct {
    uint8_t Gain;
    int16_t startV;
    int16_t endV;
    int16_t pulseAmp;   /* Square-wave amplitude (mV) */
    int16_t stepV;      /* Staircase step height (mV) */
    float freq;         /* Square-wave frequency (Hz) */
    bool setToZero;
} SWV_Params;

extern Sweep_Mode_Type Sweep_Mode;
extern IT_Params itParams;
extern CV_Params cvParams;
extern DPV_Params dpvParams;
extern SWV_Params swvParams;

extern volatile uint8_t samplingFlag;
extern Current_Range_Type Current_Range;

/* Clear cached measurement values before starting a new voltammetry run. */
void reset_Voltammogram_arrays(void);

/* Execute a full cyclic voltammetry sweep with validation and direction dispatch. */
void runCV(uint8_t Gain, uint8_t cycles, int16_t startV,
           int16_t endV, int16_t vertex1, int16_t vertex2,
           int16_t stepV, uint16_t rate, bool setToZero);

/* CV reverse-direction scan helper (vertex1 < startV, scanning negative). */
void runCVBackward(uint8_t cycles, int16_t startV, int16_t endV,
                  int16_t vertex1, int16_t vertex2, int16_t stepV, uint16_t rate);

/* CV forward-direction scan helper (vertex1 > startV, scanning positive). */
void runCVForward(uint8_t cycles, int16_t startV, int16_t endV,
                  int16_t vertex1, int16_t vertex2, int16_t stepV, uint16_t rate);

/* Chronoamperometry: hold constant potential, log current at fixed intervals. */
void runIT(int Gain, int voltage, uint32_t duration, int samplingInterval, int restTime);

/* Dispatch to the active sweep-method runner based on Sweep_Mode. */
void RunSweepMode(Sweep_Mode_Type Sweep_Mode);

/* Seed all parameter structs with safe power-on defaults. */
void InitParams(void);

/* Unpack the host-supplied command payload into the active parameter struct. */
void parseSweepModePayload(
    Sweep_Mode_Type Sweep_Mode,
    const int16_t* payload
);

/* Convert mV request → DAC code and apply to the analog front-end. */
uint16_t setPotentiostatVoltage(int16_t voltage);

/* Read the TIA output, convert ADC code → µA using the active range resistor. */
float sampleCurrent(uint8_t adcChannel);

/* Sample the reference-electrode buffer voltage (mV). */
float readREPotential(void);

/* Serialize (voltage, current) into the 6-byte host transport frame. */
void packAndSendData(Sweep_Mode_Type mode,int16_t voltage, float current);

/* Step the potential, wait for the scan interval, sample, and forward the point. */
void sampleAndSendData(Sweep_Mode_Type mode,int16_t voltage, uint16_t rate);

/* Differential pulse voltammetry — validate, pre-bias, then run direction-appropriate sweep. */
void runDPV(uint8_t Gain, int16_t startV, int16_t endV,
            int16_t pulseAmp, int16_t stepV, uint32_t pulse_width, uint32_t pulse_period,
            uint32_t quietTime, uint8_t range, bool setToZero);

/* DPV forward sweep (startV → endV, increasing potential). */
void runDPVForward(int16_t startV, int16_t endV, int16_t pulseAmp,
                   int16_t stepV, uint32_t pulse_width, uint32_t off_time);

/* DPV reverse sweep (startV → endV, decreasing potential). */
void runDPVBackward(int16_t startV, int16_t endV, int16_t pulseAmp,
                     int16_t stepV, uint32_t pulse_width, uint32_t off_time);

/* Square-wave voltammetry — validate, pre-bias, then run direction-appropriate sweep. */
void runSWV(uint8_t Gain, int16_t startV, int16_t endV,
            int16_t pulseAmp, int16_t stepV, float freq, bool setToZero);

/* SWV forward sweep (startV → endV). */
void runSWVForward(int16_t startV, int16_t endV, int16_t pulseAmp,
                   int16_t stepV, float freq);

/* SWV reverse sweep (startV → endV). */
void runSWVBackward(int16_t startV, int16_t endV, int16_t pulseAmp,
                    int16_t stepV, float freq);

/* Switch the ADG704 mux to select the TIA feedback resistor for the desired range. */
void setCurrentRange(Current_Range_Type range);

/* Return the currently active current range. */
Current_Range_Type getCurrentRange(void);

/* Return the TIA feedback resistor value (Ω) for the specified range. */
float getSampleResistor(Current_Range_Type range);

#endif // ECW_H
