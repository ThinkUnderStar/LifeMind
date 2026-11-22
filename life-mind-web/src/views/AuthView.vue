<script setup lang="ts">
/**
 * 认证屏：登录 / 注册两个模式共用一套布局。
 *
 * 为什么合并成一屏而不是两个页面：项目里没有路由，切屏靠 ref 状态；
 * 两个模式字段高度重叠（用户名、密码、验证码），拆成两个组件就要把
 * 验证码刷新、错误处理、提交中禁用各写两遍，容易两边行为不一致。
 *
 * 三条正则是后端 ValidateUtils 的逐字复制，只用于「提交前给出可读提示」，
 * 真正的裁决权始终在后端——前端放过、后端拒绝时错误文案会直接透出来。
 *
 * 视觉上分两块，职责分开：
 * - 背景是一整幅现代都市街景（一张实拍，只有一层，所以没有接缝）：
 *   街景、浮尘、天光各自以不同幅度随指针错位。
 * - 右侧一整列纸面放表单，通高、无圆角、无投影、无毛玻璃，只用一条 1px 竖线
 *   和照片分开。表单左对齐、行宽 352，是一页纸，不是一个弹窗。
 *   人在这里要输密码，周围可以有景，手上不能有花样。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import CaptchaField from '../components/CaptchaField.vue'
import { register } from '../api/auth'
import { login as enter } from '../core/store'
import { ApiError } from '../api/http'

/*
 * 背景只有一张实拍素材：Pexels 37520279，上海梧桐树下的斑马线，晴天正午偏早
 * （Pexels License：可商用、免署名、可修改）。
 *
 * 选片口径：现代都市 + 日常生活，而且得是「我们这儿」的日常。
 * 这一张几乎每一样都对：过斑马线的上班族、等灯的公交、共享单车、梧桐树影、
 * 后面玻璃幕墙的写字楼。有颜色、有太阳、有人，才是「生活」。
 * 前后否掉过：绿丘草甸（太田园）、伊斯坦布尔旧巷（古城不算都市）、
 * 圣保罗主干道（路比人多）、首尔公交站（韩文招牌，且天色发白）、
 * 外滩后街（画面里有警车）、城隍庙（工地脚手架）。
 *
 * 为什么从「分层拼贴」改成「一整张」：
 * 之前是雾山横条 + 远草 + 近草三层叠出来。问题在于山图的上部本身就是一片雾，
 * 那片雾压在暖白的天空上，必然浮出一条横贯画面的灰绿带——用户说的「中间有条杠」
 * 就是它。三层之间的半透明残余还会再叠一条。靠调 mask 只能把两条带子揉在一起，
 * 揉不掉。一整张照片没有接缝，也就没有杠。
 *
 * 调色是在图片里做的，不是靠 CSS：
 * 1. 只提亮、抬暗部、轻微收对比——**保留原来的颜色**。
 *    中间试过「双色映射」（把照片压成暖灰单色），结构是清楚了，
 *    但整幅变成黑白照片，没有生活气，被否掉。生活感来自色温：
 *    阳光的暖、梧桐的绿、路牌的蓝、车身的白，缺一样就冷。
 * 2. 顶部竖向压白（t<0.12 全强度 26%，到 0.52 归零），树梢和楼顶融进纸底，
 *    不留一条硬边。
 * 3. 淡的是天，不是主体：树冠、行人、车落在 120~200，纸底 250 左右，
 *    所以画面安静但不糊。
 */
import cityMorning from '../assets/scene/city-morning.jpg'

const shots = { cityMorning }

type Mode = 'login' | 'register'

const mode = ref<Mode>('login')
const busy = ref(false)
const error = ref('')
const notice = ref('')

const username = ref('')
const password = ref('')
const nickname = ref('')
const confirmPassword = ref('')
const captcha = ref('')
const captchaRef = ref<InstanceType<typeof CaptchaField> | null>(null)

const isRegister = computed(() => mode.value === 'register')

/* ---------------- 背景：视差与光 ---------------- */

const gateEl = ref<HTMLElement | null>(null)

let frame = 0
/** 指针在视口中的归一化位置，-1 ~ 1。只驱动景深视差 */
let normX = 0
let normY = 0

function flush() {
  frame = 0
  const gate = gateEl.value
  if (!gate) return
  gate.style.setProperty('--px', normX.toFixed(4))
  gate.style.setProperty('--py', normY.toFixed(4))
}

function onPointerMove(e: PointerEvent) {
  normX = (e.clientX / window.innerWidth - 0.5) * 2
  normY = (e.clientY / window.innerHeight - 0.5) * 2
  if (!frame) frame = requestAnimationFrame(flush)
}

function onPointerLeave() {
  normX = 0
  normY = 0
  if (!frame) frame = requestAnimationFrame(flush)
}

/*
 * 浮尘。用一组确定性伪随机，保证每次进页面分布一致（截图和肉眼对比才可靠），
 * 比 Math.random 更好复现。
 */
const dust = Array.from({ length: 30 }, (_, i) => {
  const a = ((i * 7919 + 131) % 1000) / 1000
  const b = ((i * 6361 + 277) % 997) / 997
  const c = ((i * 4153 + 619) % 991) / 991
  return {
    id: i,
    left: (a * 100).toFixed(2) + '%',
    top: (30 + b * 66).toFixed(2) + '%',
    size: (1.5 + c * 3.2).toFixed(1) + 'px',
    dur: (18 + c * 20).toFixed(1) + 's',
    delay: (-b * 34).toFixed(1) + 's',
    /* 三成偏暖像扬尘，七成偏墨绿像夜露，混着才不假 */
    warm: c > 0.68,
    op: (0.22 + b * 0.4).toFixed(2),
  }
})

onMounted(() => {
  flush()
})

onBeforeUnmount(() => {
  if (frame) cancelAnimationFrame(frame)
})

/* ---------------- 校验与提交 ---------------- */

/** 字母开头，4~16 位，字母数字下划线 */
const USERNAME_RE = /^[a-zA-Z][a-zA-Z0-9_]{3,15}$/
/** 8~20 位，不能纯数字、不能纯字母 */
const PASSWORD_RE = /^(?![0-9]+$)(?![a-zA-Z]+$)[0-9A-Za-z!@#$%^&*_-]{8,20}$/
/** 1~16 位，中文 / 字母 / 数字 / 下划线 / 连字符 */
const NICKNAME_RE = /^[\u4e00-\u9fa5a-zA-Z0-9_-]{1,16}$/

function reject(message: string) {
  // 只提示，不换验证码：这些是本地格式问题，服务端那份还没被消费，仍然可用
  error.value = message
}

async function submit() {
  if (busy.value) return
  error.value = ''
  notice.value = ''

  const name = username.value.trim()
  if (!name) return reject('请填用户名')
  if (!USERNAME_RE.test(name)) return reject('用户名要字母开头、4~16 位，只能用字母、数字、下划线')
  if (!password.value) return reject('请填密码')
  if (!PASSWORD_RE.test(password.value)) return reject('密码 8~20 位，必须同时含字母和数字')

  if (isRegister.value) {
    const alias = nickname.value.trim()
    if (!alias) return reject('请填昵称')
    if (!NICKNAME_RE.test(alias)) return reject('昵称 1~16 位，可用中文、字母、数字、下划线、连字符')
    if (password.value !== confirmPassword.value) return reject('两次输入的密码不一致')
  }

  if (captcha.value.trim().length !== 5) return reject('验证码是图片上的五个字符')

  const tempKey = captchaRef.value?.tempKey
  if (!tempKey) return reject('验证码还没取到，点一下图片重新取')

  busy.value = true
  try {
    if (isRegister.value) {
      await register({
        username: name,
        password: password.value,
        confirmPassword: confirmPassword.value,
        nickname: nickname.value.trim(),
        tempKey,
        captchaCode: captcha.value.trim(),
      })
      // 后端注册只返回 Result<Void>，不签发令牌，所以把人送回登录态
      notice.value = `注册成功，用 ${name} 登录`
      mode.value = 'login'
      password.value = ''
      confirmPassword.value = ''
      nickname.value = ''
      captchaRef.value?.refresh()
    } else {
      await enter({
        username: name,
        password: password.value,
        tempKey,
        captchaCode: captcha.value.trim(),
      })
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '请求没有完成，请再试一次'
    // 提交到服务端才算真正消费掉验证码（无论成败），所以这里必须换一张
    captchaRef.value?.refresh()
  } finally {
    busy.value = false
  }
}

function switchTo(next: Mode) {
  if (busy.value || mode.value === next) return
  mode.value = next
  error.value = ''
  notice.value = ''
  confirmPassword.value = ''
  captchaRef.value?.refresh()
}
</script>

<template>
  <main
    ref="gateEl"
    class="gate"
    @pointermove="onPointerMove"
    @pointerleave="onPointerLeave"
  >
    <!-- 背景整幅是装饰，不进无障碍树 -->
    <div class="scene" aria-hidden="true">
      <!-- 纸底：照片四周化开时露出来的颜色 -->
      <div class="sky"></div>

      <!--
        整张街景。只有一层，所以没有接缝。
        照片本身已经是暖灰淡水彩（见顶部注释），这里只留一点极浅的视差，
        让鼠标移动时「雾在动、画不动」—— 层数越少，越不该让它跟手乱跑。
      -->
      <div class="layer city">
        <img class="city-photo" :src="shots.cityMorning" alt="" aria-hidden="true" />
      </div>

      <!-- 暖白薄雾：压平对比，让照片退到纸的后面，也是卡片能浮起来的原因 -->
      <div class="city-veil"></div>

      <!-- 低处的暖光，像太阳刚翻过屋脊 -->
      <div class="sun">
        <span class="sun-core"></span>
      </div>

      <!-- 楼群之间那团天光：照片里已经亮，再补一层让天空散得更开 -->
      <div class="dawn-sun"></div>

      <!-- 浮尘 -->
      <div class="layer dust">
        <span
          v-for="p in dust"
          :key="p.id"
          class="mote"
          :class="{ warm: p.warm }"
          :style="{
            left: p.left,
            top: p.top,
            width: p.size,
            height: p.size,
            animationDuration: p.dur,
            animationDelay: p.delay,
            opacity: p.op,
          }"
        ></span>
      </div>

      <!-- 颗粒压在景物之上，把渐变从「壁纸」拉回「纸」 -->
      <div class="grain"></div>
    </div>

    <section class="card">
      <div class="inner">
        <!--
          品牌行：左边是预留的 logo 位，中间字标，右边是切换入口。
          logo 位现在只是一个描边方块——空的，不放「LOGO」字样。
          之后拿到正式标记，直接替换 .logo 的内容即可（34×34，图形撑满）。
        -->
        <div class="brand">
          <div class="logo" aria-hidden="true"></div>
          <span class="wordmark">LifeMind</span>
          <button
            type="button"
            class="switch"
            :disabled="busy"
            @click="switchTo(isRegister ? 'login' : 'register')"
          >
            {{ isRegister ? '登录' : '注册' }}
          </button>
        </div>

        <header class="head">
          <h1>{{ isRegister ? '创建账号' : '欢迎回来' }}</h1>
          <p class="lede">日程、待办和提醒，说一句就行</p>
        </header>

        <!--
          字段不带「标签在上方」那一行：一屏五个标签会把表单拉长成一张表格。
          改成一列等宽输入框，靠 placeholder 说明要填什么，
          语义交给 aria-label（视觉上省掉，无障碍不减）。
        -->
        <form class="form" @submit.prevent="submit">
        <input
          v-model="username"
          class="field"
          type="text"
          autocomplete="username"
          spellcheck="false"
          placeholder="用户名"
          aria-label="用户名"
          :disabled="busy"
        />

        <input
          v-if="isRegister"
          v-model="nickname"
          class="field"
          type="text"
          autocomplete="nickname"
          spellcheck="false"
          placeholder="昵称（怎么称呼你）"
          aria-label="昵称"
          :disabled="busy"
        />

        <input
          v-model="password"
          class="field"
          type="password"
          :autocomplete="isRegister ? 'new-password' : 'current-password'"
          :placeholder="isRegister ? '密码（8~20 位，字母加数字）' : '密码'"
          aria-label="密码"
          :disabled="busy"
        />

        <input
          v-if="isRegister"
          v-model="confirmPassword"
          class="field"
          type="password"
          autocomplete="new-password"
          placeholder="再输入一次密码"
          aria-label="确认密码"
          :disabled="busy"
        />

        <CaptchaField ref="captchaRef" v-model="captcha" :disabled="busy" />

        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <p v-if="notice" class="notice" role="status">{{ notice }}</p>

        <button class="btn submit" type="submit" :disabled="busy">
          {{ busy ? (isRegister ? '正在注册…' : '正在登录…') : isRegister ? '注册' : '登录' }}
        </button>
      </form>

      <p class="foot">
        {{ isRegister ? '注册后自动开通普通用户权限' : '验证码看不清，点一下图片换一张' }}
      </p>
      </div>
    </section>
  </main>
</template>

<style scoped>
/*
 * 局部变量，只作用于这一屏。
 * 全站 token 定义的是「使用中的工具」，这里要的是「推门进来」的那点温度，
 * 所以另起一套暖色，不改 tokens.css，进来之后的主界面一点不受影响。
 */
.gate {
  --warm: #eea961;
  --warm-deep: #d98b46;
  --leaf: 47, 93, 80;

  position: relative;
  display: grid;
  /*
   * 列宽必须显式给 minmax(0, 1fr)。
   * 隐式列的 auto 最小值是 min-content，而卡片里「验证码输入 + 换一张」那一行
   * 的 min-content 接近 440px——窄屏下这一列被撑到 440，grid 容器跟着溢出，
   * 卡片被居中到视口外面，右边一截直接切掉（375 宽时就是如此）。
   * minmax(0, 1fr) 允许列收缩到比内容更窄，卡片才能停在留白里。
   */
  grid-template-columns: minmax(0, 1fr);
  place-items: center;
  min-height: 100%;
  padding: var(--s6) var(--s4);
  /*
   * 这里不能 overflow: hidden。注册模式五个字段时卡片接近视口高度，
   * 小屏（如 375×667）会超出，裁掉的正是按钮和底部提示。
   * 景物溢出改由 .scene 自己裁。
   */
  background: linear-gradient(180deg, #fffdf9 0%, #fdf8ef 46%, #f7f0e3 100%);
  /* JS 只写这两个值，各层的视差全部从这里派生 */
  --px: 0;
  --py: 0;
}

/* ================= 背景 ================= */

.scene {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

/*
 * 纸底。照片四周化开之后露出来的就是它，所以它必须是「纸」而不是「天」。
 * 城市版的天空在照片里，这里只留一点左上暖光和一整片暖白，
 * 不再需要以前那套「淡青走到暖米」的天空渐变。
 */
.sky {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(50% 40% at 6% 0%, rgba(255, 236, 205, 0.35) 0%, rgba(255, 238, 211, 0) 68%),
    linear-gradient(168deg, #fbf8f1 0%, #faf7ef 42%, #f6f2e8 72%, #f2eee3 100%);
}

/*
 * 整张街景。
 *
 * 系数从 5 提到 8：5px 在 1440 宽的屏上只有 0.35% 的位移，鼠标横穿整屏时
 * 画面几乎不动，等于没有互动。8px 刚好能感觉到「画比我慢半拍」，
 * 又不至于晃成幻灯片——再往上（试到 12）卡片的边框会和背景产生相对滑动，
 * 眼睛会以为卡片在漂。
 *
 * 幅度加大之后，出血量要跟上：.layer 的 inset 是 -90px，横向 8px 有十倍余量。
 */
.city {
  --k: 8;
}

.city-photo {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  /* 取景略偏右：把过街的人往左边让开，卡片底下留出人行道和玻璃楼 */
  object-position: 52% 52%;
  /*
   * 0.6px 不是「虚」，是把数码照片的锐边磨掉一层，让它像印在纸上。
   * 再大就回到那个「糊得看不清」的坑里了——清晰度是底线。
   * 饱和度不再往下压：这张是靠颜色撑「生活感」的，压掉就成了灰片。
   */
  filter: blur(0.6px) saturate(0.98);
  /*
   * 四周收边：照片的硬直角一旦露出来，整幅画就变成一张贴纸。
   * 四条边各留 5%~7% 化进纸色，边缘就没了。
   */
  -webkit-mask-image:
    linear-gradient(90deg, transparent 0%, #000 7%, #000 93%, transparent 100%),
    linear-gradient(180deg, transparent 0%, #000 5%, #000 93%, transparent 100%);
  mask-image:
    linear-gradient(90deg, transparent 0%, #000 7%, #000 93%, transparent 100%),
    linear-gradient(180deg, transparent 0%, #000 5%, #000 93%, transparent 100%);
  -webkit-mask-composite: source-in;
  mask-composite: intersect;
}

/*
 * 薄雾。这张天空在素材里已经压成纸色，所以这一层只做一件事：
 * 让卡片背后的那一竖条稍微退后一点，别跟卡片里的字抢。
 * 之前开到 0.4~0.7 是为了压灰，现在再压就把颜色一起洗掉了。
 */
.city-veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(255, 252, 246, 0.1) 0%,
    rgba(255, 252, 246, 0.05) 30%,
    rgba(255, 251, 244, 0.02) 55%,
    rgba(255, 250, 242, 0.1) 100%
  );
  pointer-events: none;
}

/*
 * 楼群缝隙里的天光。城市这张的天亮在高楼之间，补一层暖白光晕把它拉开，
 * 成为整幅画唯一的高光——视线先落在它上面，再顺着街道往纵深走。
 */
.dawn-sun {
  position: absolute;
  left: 52%;
  top: 16%;
  width: clamp(150px, 18vw, 280px);
  aspect-ratio: 1;
  translate: -50% -50%;
  border-radius: 50%;
  background: radial-gradient(
    circle,
    rgba(255, 251, 236, 0.32) 0%,
    rgba(255, 244, 214, 0.14) 34%,
    rgba(255, 236, 200, 0) 72%
  );
  filter: blur(36px);
  mix-blend-mode: screen;
  /*
   * 天光跟着指针「反向」走，而且幅度比街景大一倍——它离得近（浮在楼前），
   * 近的东西视差本来就该更大。12px 时几乎看不出，提到 18px 才有前后关系。
   */
  transform: translate3d(calc(var(--px) * -18px), calc(var(--py) * -12px), 0);
  transition: transform 780ms cubic-bezier(0.22, 0.61, 0.36, 1);
}

/*
 * 太阳：外框只负责视差，内层负责呼吸。
 * 两者都用 transform，合在一起会互相覆盖，必须分开。
 */
.sun {
  position: absolute;
  left: -16%;
  top: -30%;
  width: 72vmax;
  height: 72vmax;
  transform: translate3d(calc(var(--px) * -15px), calc(var(--py) * -11px), 0);
  transition: transform 780ms cubic-bezier(0.22, 0.61, 0.36, 1);
}

.sun-core {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: radial-gradient(
    circle,
    rgba(255, 219, 165, 0.12) 0%,
    rgba(255, 226, 182, 0.06) 34%,
    rgba(255, 232, 196, 0) 66%
  );
  animation: breathe 15s ease-in-out infinite alternate;
}

/*
 * 景深：每个 layer 有自己的 --k。
 * 现在只剩两层——街景 8px、浮尘 11px。层数少了，差距反而要更小，
 * 不然「画不动、尘在飘」的错觉会变成「画也在飘」。
 * 浮尘只比街景多一点：它离镜头最近，但单颗才 3~5px 大，位移再大就飞出去了。
 */
.layer {
  position: absolute;
  /*
   * 四周各留一圈出血。层是按 --k 位移的，若 inset 为 0，
   * 一挪就在右边缘露出一条与纸底不同色的白条（实测出现过）。
   */
  inset: -40px -90px;
  transform: translate3d(
    calc(var(--px) * var(--k) * 1px),
    calc(var(--py) * var(--k) * 0.55px),
    0
  );
  /*
   * 820ms 偏慢：跟手感被拖成「慢镜头」，鼠标停下之后画面还在走完最后那几像素。
   * 700ms 在 8px 的幅度下既跟得上，又不会一抖一抖。
   */
  transition: transform 700ms cubic-bezier(0.22, 0.61, 0.36, 1);
  will-change: transform;
}

.dust {
  --k: 11;
}

/* 山、草、叶三层随城市版一起下线，原因见文件顶部的素材注释。 */

/* 浮尘：向上飘、明灭。跟指针无关，页面静止时背景也在动 */
.mote {
  position: absolute;
  border-radius: 50%;
  background: rgba(var(--leaf), 0.55);
  box-shadow: 0 0 6px rgba(var(--leaf), 0.3);
  animation-name: rise;
  animation-timing-function: linear;
  animation-iteration-count: infinite;
}

.mote.warm {
  background: rgba(255, 234, 196, 0.95);
  box-shadow: 0 0 8px rgba(255, 216, 160, 0.75);
}

.grain {
  position: absolute;
  inset: 0;
  opacity: 0.045;
  mix-blend-mode: multiply;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='160' height='160' filter='url(%23n)'/%3E%3C/svg%3E");
  background-size: 160px 160px;
}

@keyframes breathe {
  from {
    transform: scale(1);
    opacity: 0.82;
  }
  to {
    transform: scale(1.07);
    opacity: 1;
  }
}

@keyframes rise {
  0% {
    transform: translate3d(0, 0, 0) scale(0.85);
    opacity: 0;
  }
  12% {
    opacity: 1;
  }
  86% {
    opacity: 0.85;
  }
  100% {
    transform: translate3d(26px, -34vh, 0) scale(1.05);
    opacity: 0;
  }
}

/* ================= 纸面：留白、细线，不做玻璃 ================= */

/*
 * 这一版把「AI 味」的四样东西逐条拆了：
 * 1. 半透明毛玻璃 + backdrop-filter —— 换成实色纸面 #fbfaf7，不再让背景透过字。
 * 2. 18px 大圆角 + 外发光 —— 换成 4px 圆角（桌面端 0）、1px 底线，无投影。
 * 3. 内容全部居中堆叠 —— 改成左对齐，标题、说明、字段、按钮共用一条左边线。
 * 4. 胶囊分段控件 —— 去掉，换成品牌行最右一个纯文字切换入口。
 * 按钮也从宽字距 + 投影改成正常字距的实心墨条：字距一拉开就成模板了。
 */
.card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 400px;
  /* 作为 grid item 时默认 min-width: auto 会被内容撑开 */
  min-width: 0;
  padding: 32px 28px 26px;
  border: 1px solid rgba(23, 24, 26, 0.07);
  border-radius: 4px;
  background: #fbfaf7;
}

/* ---------- 品牌行：logo 位 + 字标 + 切换 ---------- */

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  /* 一条发丝线把「刊头」和正文分开：像一页纸的页眉，不像一个弹窗的标题栏 */
  padding-bottom: 22px;
  margin-bottom: 34px;
  border-bottom: 1px solid rgba(23, 24, 26, 0.07);
}

/*
 * 预留 logo 位：34×34 的空描边方块，不放占位文字。
 * 之后把图形放进这里即可，给它 width/height:100% + object-fit:contain，
 * 品牌行的节奏不用动。
 */
.logo {
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  /* 不填白底：填了就像一个空按钮。只留一圈细边，读作「标记待放」 */
  border: 1px solid rgba(23, 24, 26, 0.14);
  border-radius: 9px;
}

.wordmark {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--ink);
}

/* 切换入口只是一个字：登录 / 注册。不用胶囊、不用下划线常驻 */
.switch {
  margin-left: auto;
  padding: 2px 0;
  background: none;
  color: var(--muted);
  font-size: 12.5px;
  letter-spacing: 0.02em;
  border-bottom: 1px solid transparent;
  transition: color 180ms var(--ease), border-color 180ms var(--ease);
}

.switch:hover:not(:disabled) {
  color: var(--ink);
  border-bottom-color: rgba(23, 24, 26, 0.4);
}

/* ---------- 头部 ---------- */

h1 {
  font-size: 32px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: 0;
  color: var(--ink);
}

.lede {
  margin-top: 10px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.6;
}

/* ---------- 表单 ---------- */

.form {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 36px;
}

/*
 * 输入框：白底 + 1px 灰线 + 3px 圆角。没有内阴影、没有渐变、没有背景透明。
 * 聚焦时把边框压成墨黑，并用 inset 阴影补足到 2px——只改视觉厚度，不改盒模型，
 * 五行字段不会因为聚焦而上下抖一跳。
 */
.field {
  width: 100%;
  height: 46px;
  padding: 0 14px;
  border: 1px solid rgba(23, 24, 26, 0.11);
  border-radius: 3px;
  background: #fff;
  font-size: 14px;
  transition: border-color 160ms var(--ease), box-shadow 160ms var(--ease);
}

.field::placeholder {
  /* 比 --muted-soft 再淡一档：placeholder 是提示，不该抢已填内容的视线 */
  color: rgba(23, 24, 26, 0.28);
}

.field:hover:not(:disabled):not(:focus) {
  border-color: rgba(23, 24, 26, 0.22);
}

.field:focus {
  outline: none;
  border-color: var(--ink);
  box-shadow: inset 0 0 0 1px var(--ink);
}

.field:focus-visible {
  outline: none;
}

/* 提示不用左侧色条、不用图标：一行小字，颜色说话 */
.error,
.notice {
  font-size: 12.5px;
  line-height: 1.55;
}

.error {
  color: var(--alert);
}

.notice {
  color: var(--signal);
}

/* ---------- 提交 ---------- */

.submit {
  justify-content: center;
  width: 100%;
  height: 48px;
  margin-top: 8px;
  border: 1px solid var(--ink);
  border-radius: 3px;
  background: var(--ink);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  letter-spacing: 0.02em;
  transition: background 160ms var(--ease), border-color 160ms var(--ease);
}

.submit:hover:not(:disabled) {
  background: #2a2b2e;
  border-color: #2a2b2e;
}

.submit:active:not(:disabled) {
  background: #0f1011;
  border-color: #0f1011;
}

.foot {
  margin-top: 16px;
  color: var(--muted-soft);
  font-size: 11.5px;
  line-height: 1.6;
}

/*
 * 宽屏：照片在左，纸面在右，两者是「一整块屏幕」的两半，不是「画上贴了一张卡」。
 *
 * 之前是浮在照片上的圆角玻璃卡：半透明 + 大圆角 + 外发光 + 内容全部居中。
 * 这四样凑在一起就是那种生成式模板的脸。现在纸面从顶到底通高，没有圆角、
 * 没有投影、没有毛玻璃，只留一条 1px 竖线分隔——分界清楚，画面也完整。
 * 表单在纸面里垂直居中、左对齐，读起来是一页纸，不是一个弹窗。
 *
 * 位置有讲究：这一段必须排在 .card 基础样式之后。同一优先级下后写的胜出，
 * 早先把它写在 .gate 旁边，结果 max-width / border-radius / padding 全被
 * 后面的基础规则盖掉，面板右边留出 25px 缝、四个角还是圆的。
 */
@media (min-width: 981px) {
  .gate {
    grid-template-columns: minmax(0, 1fr) clamp(392px, 30vw, 460px);
    place-items: stretch;
    padding: 0;
  }

  .card {
    grid-column: 2;
    display: grid;
    align-content: center;
    max-width: none;
    padding: 48px clamp(40px, 3.4vw, 60px);
    border: 0;
    border-left: 1px solid rgba(23, 24, 26, 0.08);
    border-radius: 0;
  }

  .inner {
    width: 100%;
    max-width: 352px;
    justify-self: center;
  }
}

/* ================= 小屏与降级 ================= */

/* 触摸屏没有指针，视差无从谈起，只留自发运动（浮尘、呼吸） */
@media (hover: none) {
  .layer,
  .sun,
  .dawn-sun {
    transform: none !important;
    transition: none;
  }
}

@media (max-width: 720px) {
  /*
   * 竖屏时整张街景会被 cover 裁成窄条，街道纵深容易跑到卡片后面。
   * 取景往左下挪一点，让楼群和路面留在卡片上方那片可见区里。
   */
  .city-photo {
    object-position: 33% 48%;
  }

  /* 卡片几乎盖满屏幕，只剩顶部一条缝和底部一截，雾得收一档才看得出是街景 */
  .city-veil {
    background: linear-gradient(
      180deg,
      rgba(255, 252, 246, 0.12) 0%,
      rgba(255, 252, 246, 0.06) 40%,
      rgba(255, 251, 244, 0.12) 100%
    );
  }
}

@media (max-width: 420px) {
  .gate {
    padding: var(--s5) var(--s3);
  }

  /* 窄屏把内边距收回来：28px 的左右留白在 375 宽上要吃掉 56px，输入框会被压得很窄 */
  .card {
    padding: 24px 18px 20px;
  }

  .brand {
    margin-bottom: 32px;
  }

  .form {
    margin-top: 26px;
  }

  h1 {
    font-size: 26px;
  }
}

/* 系统级减少动效：所有位移与自发运动一起停 */
@media (prefers-reduced-motion: reduce) {
  .layer,
  .sun,
  .dawn-sun {
    transform: none !important;
    transition: none;
  }

  .sun-core,
  .mote {
    animation: none;
  }

  .mote {
    opacity: 0.3 !important;
  }
}
</style>
