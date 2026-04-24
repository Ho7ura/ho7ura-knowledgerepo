# SquareLine Studio UI 移植到 CodeBlocks 工程指南

## 一、SquareLine 导出文件结构

```
uicode/
├── ui.c              入口文件，包含 ui_init() 和 ui_destroy()
├── ui.h              头文件，声明所有屏幕和字体
├── ui_helpers.c      辅助函数实现（不要修改）
├── ui_helpers.h      辅助函数声明
├── ui_events.h       事件回调声明，自定义逻辑写这里
├── project.info      项目元信息
├── filelist.txt      文件列表
├── CMakeLists.txt    CMake 构建文件（CodeBlocks 不用）
│
├── screens/          每个屏幕一个 .c/.h 文件
│   ├── ui_Screen1.c
│   └── ui_Screen1.h
│
├── fonts/            字体源码文件
│   ├── ui_font_xxx.c
│   └── ...
│
├── components/       组件钩子
│   └── ui_comp_hook.c
│
└── images/           图片资源（如果有的话）
```

**关键文件说明**：
- `ui.c / ui.h`：入口，main.c 调用 `ui_init()` 即可
- `ui_events.h`：放你的事件回调函数声明
- `screens/`：每个屏幕的界面代码，不要手动修改
- `fonts/`：字体文件，中文字体可能很大（几MB）

---

## 二、颜色深度必须匹配

这是最常见的编译错误来源。

| 位置 | 配置项 |
|------|--------|
| SquareLine Studio | Project Settings → Color Depth |
| 工程的 `lv_conf.h` | `LV_COLOR_DEPTH` 宏 |

两者必须一致。SquareLine 导出的 `ui.c` 里有检查：

```c
#if LV_COLOR_DEPTH != 16
    #error "LV_COLOR_DEPTH should be 16bit to match SquareLine Studio's settings"
#endif
```

**解决方案**：
1. 推荐在 SquareLine 中修改 Color Depth 后重新导出
2. 临时方案：删除或注释掉 `ui.c` 中的检查代码

---

## 三、LVGL 版本要一致

SquareLine 导出时可选择 LVGL 版本，必须与目标工程的 LVGL 版本匹配。

**版本不匹配的表现**：
- API 函数名或参数不同
- 宏定义不存在
- 结构体成员变化
- 编译错误或运行时崩溃

**检查方法**：
- SquareLine 导出的文件头部有注释：`// LVGL version: 8.3.11`
- 目标工程的 `lvgl.h` 或 `lv_conf.h` 可查看版本

---

## 四、头文件路径问题

SquareLine 生成的代码使用以下包含方式：

```c
// ui.h 中
#include "lvgl/lvgl.h"
#include "screens/ui_Screen1.h"
```

**这意味着**：
1. 编译器需要能找到 `lvgl/lvgl.h`，所以搜索路径要包含工程根目录
2. `screens/ui_Screen1.h` 需要搜索路径包含 `uicode` 目录

**main.c 中添加**：
```c
#include "uicode/ui.h"
```

---

## 五、CodeBlocks 配置步骤

### 1. 添加编译器搜索路径

```
Project → Build options → Search directories → Compiler

添加：
- uicode
```

这样编译器能找到 `ui.h`、`screens/` 下的头文件等。

### 2. 添加源文件到工程

```
Project → Add files recursively

选择 uicode 目录，添加所有 .c 文件
```

不添加源文件会报 "undefined reference" 链接错误。

### 3. main.c 调用

```c
#include "uicode/ui.h"

int main(void)
{
    lv_init();
    // 驱动初始化...

    ui_init();  // 调用 SquareLine 生成的 UI 初始化

    while(1) {
        lv_task_handler();
        // ...
    }
}
```

---

## 六、标准移植流程

```
步骤 1：确认目标工程信息
        - LVGL 版本（查看 lvgl.h 或 lv_conf.h）
        - 颜色深度（查看 lv_conf.h 中的 LV_COLOR_DEPTH）

步骤 2：SquareLine 设置匹配
        - Project Settings → Color Depth 改为与目标一致
        - 确认 LVGL 版本选择正确
        - 导出 UI 文件

步骤 3：复制文件
        - 将导出的 ui 文件夹复制到工程目录

步骤 4：CodeBlocks 配置
        - 添加搜索路径
        - 添加源文件到工程

步骤 5：main.c 集成
        - #include "uicode/ui.h"
        - 在初始化后调用 ui_init()

步骤 6：编译测试
```

---

## 七、常见问题排查

### 问题 1：找不到头文件

**错误信息**：
```
fatal error: ui.h: No such file or directory
```

**原因**：编译器搜索路径没配置

**解决**：Project → Build options → Search directories → Compiler 添加 `uicode`

---

### 问题 2：未定义的引用

**错误信息**：
```
undefined reference to `ui_init'
```

**原因**：源文件没添加到工程

**解决**：Project → Add files recursively 添加 `uicode` 下所有 .c 文件

---

### 问题 3：LV_COLOR_DEPTH 错误

**错误信息**：
```
#error "LV_COLOR_DEPTH should be 16bit to match SquareLine Studio's settings"
```

**原因**：颜色深度不匹配

**解决**：
- 方案 A：SquareLine 重新导出匹配的颜色深度
- 方案 B：修改 `ui.c` 删除检查代码

---

### 问题 4：字体太大导致编译慢或内存不足

**表现**：中文字体文件可能有几 MB

**解决**：
- 只包含需要的汉字（SquareLine 中设置字符范围）
- 或使用外部字体文件 + LVGL 文件系统

---

## 八、本次实践中的问题回顾

1. **颜色深度不匹配**：SquareLine 导出 16bit，工程配置 32bit
2. **导出路径不清楚**：SquareLine 导出目录与预期不同
3. **检查代码有误导**：`ui.c` 中的错误信息与实际检查不一致
4. **配置步骤不熟悉**：不知道要在 CodeBlocks 添加搜索路径和源文件

---

## 九、后续学习建议

1. 自己独立完成一次完整移植流程
2. 尝试在 `ui_events.h` 中添加按钮事件回调
3. 学习如何动态修改 UI 元素（通过 `screens/ui_Screen1.h` 中导出的变量）
4. 了解如何在 SquareLine 中添加自定义事件

---

*文档生成时间：2026-04-23*
