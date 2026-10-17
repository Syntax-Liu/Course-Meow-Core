package com.nexoraism.coursemeow.data

import com.nexoraism.coursemeow.data.lang.DeCopy
import com.nexoraism.coursemeow.data.lang.DeExtra
import com.nexoraism.coursemeow.data.lang.EnCopy
import com.nexoraism.coursemeow.data.lang.EnExtra
import com.nexoraism.coursemeow.data.lang.EnGbCopy
import com.nexoraism.coursemeow.data.lang.FrCopy
import com.nexoraism.coursemeow.data.lang.FrExtra
import com.nexoraism.coursemeow.data.lang.ZhHantCopy
import com.nexoraism.coursemeow.data.lang.ZhHantExtra

/**
 * 可改界面文案清单。
 *
 * UI 主题里的 `copy` 只能改**界面上写死的固定文案**（按钮名、标题、说明文字……），
 * 改不了课表内容 —— 课程名、教师、上课地点、备注这些都是用户自己的数据，
 * 属于「内容」而不是「界面」。
 *
 * 清单有两个来源：
 * 1. **内置翻译表的键**（[ZhHantCopy] 等）：能被翻译的文案，就是界面上固定的文案。
 *    好处是不会漏 —— 新写的界面文案只要补上翻译，就自动出现在清单里；
 *    也不会混进个人数据：课表内容从来不需要翻译，也就不在表里。
 * 2. **源码抽取的补充部分**（[UiCopyLiterals]）：页脚注释这类只出现一次的说明文字，
 *    从没进过翻译表，由脚本从源码里抽出来补齐。
 *
 * 两个来源都不含用户数据，所以在任何设备上生成的都是同一份模板。
 */
object UiCopyCatalog {

    /**
     * 清单内容：先按翻译表的写作顺序（大致按界面从主到次，相邻几条通常属于同一页面，
     * 翻起来好找），再追加源码补充的那一批。
     */
    val keys: List<String> = buildList {
        addAll(ZhHantCopy.keys)
        addAll(ZhHantExtra.keys)
        addAll(EnCopy.keys)
        addAll(EnExtra.keys)
        addAll(EnGbCopy.keys)
        addAll(DeCopy.keys)
        addAll(DeExtra.keys)
        addAll(FrCopy.keys)
        addAll(FrExtra.keys)
        addAll(UiCopyLiterals)
    }.distinct()

    private val index: Set<String> = keys.toSet()

    /** 这条文案是否属于「界面固定文案」。 */
    fun contains(key: String): Boolean = key in index

    /** 清单条数。 */
    val size: Int get() = keys.size
}
