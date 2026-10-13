package com.mozhi.reader.ai.genui

/**
 * 提示词里的完整示例。DJJ 的结论：一个完整、能跑的例子比写多少条规则都管用。
 * 这两段同时是单测样例（GenUiCompilerTest）：必须零诊断编译并渲染成功，改动后测试会守住。
 *
 * 刻意使用占位的人物与情节，避免把某本具体的书带进其他对话。
 */
object GenUiPromptExamples {

    /** 附录 A：读书场景的小测（基础写法，两种界面风格都可用）。 */
    val QUIZ: String = """
这一章线索不少，我出两道题考考你，答完点「交卷」看解析。

{@body const [answers,setAnswers] = useState({})}
{@body const [submitted,setSubmitted] = useState(false)}
{@body const QUESTIONS = [{id:"q1",ask:"主角离开故乡的直接原因是什么？",options:["被人陷害","主动求学","奉命出使"],right:0,chapter:3,quote:"（这里必须是已读原文里的一句，逐字）"},{id:"q2",ask:"旧信是谁留下的？",options:["师父","邻家少年","母亲"],right:2,chapter:5,quote:"（原文）"}]}
{@body const score = QUESTIONS.filter(q => answers[q.id] === q.right).length}
{@body const done = Object.keys(answers).length === QUESTIONS.length}

<card gap={3}>
  <row justify="between">
    <title size="sm">第五章 · 小测</title>
    <badge color={submitted ? (score === QUESTIONS.length ? "success" : "info") : "secondary"}>{submitted ? score + " / " + QUESTIONS.length : "未交卷"}</badge>
  </row>
  {#each QUESTIONS as q, i}
    <text>**{i + 1}. {q.ask}**</text>
    <radio-group value={answers[q.id] ?? -1} onChange={v => setAnswers(prev => ({...prev, [q.id]: v}))}>
      {#each q.options as opt, j}<radio value={j}>{opt}</radio>{/each}
    </radio-group>
    {#if submitted}
      <caption color={answers[q.id] === q.right ? "success" : "danger"}>{answers[q.id] === q.right ? "答对了" : "正确答案：" + q.options[q.right]}</caption>
      <Quote chapter={q.chapter} text={q.quote}/>
    {/if}
  {/each}
  <row gap={2}>
    <button variant="solid" disabled={!done || submitted} onClick={() => setSubmitted(true)}>交卷</button>
    {#if submitted}<button variant="outline" onClick={() => app.send("再出两道更难的题，范围还是到第五章")}>再来两道</button>{/if}
  </row>
</card>

答错的那题，回去看看第三章开头那段对话就明白了。
""".trim()

    /** 附录 C：「自由」界面风格的示例，示范样式集中定义、随状态变色、主题变量与一个强调色。 */
    val STYLED: String = """
想知道你更像书里哪一位？拖动三个滑块，结果实时变化。

{@body const [brave,setBrave] = useState(50)}
{@body const [loyal,setLoyal] = useState(50)}
{@body const [clever,setClever] = useState(50)}
{@body const ROLES = [{name:"主角",v:[80,70,60],tone:"#c65f76",line:"冲在最前面的人"},{name:"军师",v:[40,60,95],tone:"#567bce",line:"三步之外就看到结局"},{name:"老兵",v:[70,95,40],tone:"#9a7b4f",line:"认定了就不回头"}]}
{@body const dist = r => Math.hypot(r.v[0] - brave, r.v[1] - loyal, r.v[2] - clever)}
{@body const best = ROLES.reduce((a, b) => dist(b) < dist(a) ? b : a)}
{@body const S = {hero:{background:`radial-gradient(circle at 18% 0%, ${'$'}{best.tone}55, transparent 62%), var(--card)`, borderRadius:28, padding:22, transition:"background 420ms ease"}, eyebrow:{letterSpacing:"0.24em", color:"var(--muted)"}, name:{fontFamily:"serif", fontSize:38, color:best.tone, letterSpacing:"0.06em", animation:"pop 360ms"}}}

<box style={S.hero} gap={3}>
  <caption style={S.eyebrow}>你 最 像</caption>
  <text style={S.name} key={best.name}>{best.name}</text>
  <caption>{best.line}</caption>
  <divider style="opacity:.4"/>
  <slider label="勇气" value={brave} onChange={setBrave} min={0} max={100}/>
  <slider label="忠诚" value={loyal} onChange={setLoyal} min={0} max={100}/>
  <slider label="谋略" value={clever} onChange={setClever} min={0} max={100}/>
</box>
""".trim()
}
