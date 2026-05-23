export type Lang = "en" | "ar";

export const LANGUAGES: { code: Lang; label: string; dir: "ltr" | "rtl" }[] = [
  { code: "en", label: "English", dir: "ltr" },
  { code: "ar", label: "العربية", dir: "rtl" },
];

type Dict = Record<string, string>;

const en: Dict = {
  // Brand panel
  "brand.tagline": "Your IPTV, beautifully on the big screen.",
  "brand.feat1": "Live TV from M3U & Xtream Codes",
  "brand.feat2": "Built for the TV remote",
  "brand.feat3": "Fast browsing for large playlists",

  // Provider setup
  "setup.title": "Add a provider",
  "setup.subtitle": "Connect a Live TV source to get started.",
  "setup.tabXtream": "Xtream Codes",
  "setup.tabM3u": "M3U URL",
  "setup.displayName": "Display name (optional)",
  "setup.displayNamePh": "My Provider",
  "setup.playlistUrl": "Playlist URL",
  "setup.serverUrl": "Server URL",
  "setup.username": "Username",
  "setup.password": "Password",
  "setup.add": "Add provider",
  "setup.connecting": "Connecting…",
  "setup.done": "Done",
  "setup.connect": "Connect",
  "setup.remove": "Remove",
  "setup.savedProviders": "Your providers",
  "setup.errPlaylistUrl": "Playlist URL is required",
  "setup.errXtreamFields": "Server, username and password are required",
  "setup.okPlaylistAdded": "Playlist added",
  "setup.okConnected": "Connected and saved",
  "setup.errAddFailed": "Failed to add provider",

  // Home dashboard
  "home.live": "Live",
  "home.movies": "Movies",
  "home.series": "Series",
  "home.sportsGuide": "Sports Guide",
  "home.changePlaylist": "Change Playlist",
  "home.settings": "Settings",
  "home.reload": "Reload",
  "home.syncing": "Syncing your library…",
  "home.exit": "Exit",
  "home.expires": "Current playlist expires: {date}",
  "home.unlimited": "unlimited",
  "home.soon": "Coming soon",

  // Connection status
  "status.online": "Connected",
  "status.offline": "Not connected",

  // Common / browse
  "common.back": "Back",
  "common.search": "Search",
  "common.searchPh": "Search by name…",
  "common.sort": "Sort",
  "common.sortAdded": "Recently added",
  "common.sortName": "Name",
  "common.categories": "Categories",
  "common.all": "All",
  "common.nothingHere": "Nothing here yet",

  // Content types
  "content.live": "Live TV",
  "content.movies": "Movies",
  "content.series": "Series",
  "content.loading": "Loading {type}…",
  "content.backToProviders": "Press Back to return to providers",
  "content.failed": "Failed to load",

  // Episodes
  "episodes.loading": "Loading episodes…",
  "episodes.series": "Series",
  "episodes.seasons": "Seasons",
  "episodes.season": "Season {n}",
  "episodes.none": "No episodes",
  "episodes.xtreamOnly": "Series are only available for Xtream providers",
  "episodes.failed": "Failed to load episodes",
  "episodes.back": "Press Back to return",

  // Player
  "player.tuning": "Tuning in…",
  "player.failed": "Playback failed",
  "player.back": "Press Back to return",
  "player.blocked": "Playback was blocked",
  "player.cantLoad": "Unable to load this stream",
};

const ar: Dict = {
  "brand.tagline": "بثّك المباشر، بأناقة على الشاشة الكبيرة.",
  "brand.feat1": "بث مباشر من M3U و Xtream Codes",
  "brand.feat2": "مصمّم للتحكّم بريموت التلفزيون",
  "brand.feat3": "تصفّح سريع للقوائم الكبيرة",

  "setup.title": "إضافة مزوّد",
  "setup.subtitle": "وصّل مصدر بث مباشر للبدء.",
  "setup.tabXtream": "Xtream Codes",
  "setup.tabM3u": "رابط M3U",
  "setup.displayName": "اسم العرض (اختياري)",
  "setup.displayNamePh": "مزوّدي",
  "setup.playlistUrl": "رابط القائمة",
  "setup.serverUrl": "رابط الخادم",
  "setup.username": "اسم المستخدم",
  "setup.password": "كلمة المرور",
  "setup.add": "إضافة المزوّد",
  "setup.connecting": "جارٍ الاتصال…",
  "setup.done": "تم",
  "setup.connect": "اتصال",
  "setup.remove": "حذف",
  "setup.savedProviders": "مزوّداتك",
  "setup.errPlaylistUrl": "رابط القائمة مطلوب",
  "setup.errXtreamFields": "الخادم واسم المستخدم وكلمة المرور مطلوبة",
  "setup.okPlaylistAdded": "تمت إضافة القائمة",
  "setup.okConnected": "تم الاتصال والحفظ",
  "setup.errAddFailed": "تعذّرت إضافة المزوّد",

  "home.live": "البث المباشر",
  "home.movies": "أفلام",
  "home.series": "مسلسلات",
  "home.sportsGuide": "دليل الرياضة",
  "home.changePlaylist": "تغيير القائمة",
  "home.settings": "الإعدادات",
  "home.reload": "إعادة تحميل",
  "home.syncing": "جاري تحديث مكتبتك…",
  "home.exit": "خروج",
  "home.expires": "تنتهي القائمة الحالية: {date}",
  "home.unlimited": "غير محدودة",
  "home.soon": "قريباً",

  "status.online": "متصل",
  "status.offline": "غير متصل",

  "common.back": "رجوع",
  "common.search": "بحث",
  "common.searchPh": "ابحث بالاسم…",
  "common.sort": "ترتيب",
  "common.sortAdded": "الأحدث إضافة",
  "common.sortName": "الاسم",
  "common.categories": "التصنيفات",
  "common.all": "الكل",
  "common.nothingHere": "لا يوجد شيء هنا بعد",

  "content.live": "البث المباشر",
  "content.movies": "أفلام",
  "content.series": "مسلسلات",
  "content.loading": "جارٍ تحميل {type}…",
  "content.backToProviders": "اضغط رجوع للعودة إلى المزوّدين",
  "content.failed": "فشل التحميل",

  "episodes.loading": "جارٍ تحميل الحلقات…",
  "episodes.series": "مسلسلات",
  "episodes.seasons": "المواسم",
  "episodes.season": "الموسم {n}",
  "episodes.none": "لا توجد حلقات",
  "episodes.xtreamOnly": "المسلسلات متاحة لمزوّدي Xtream فقط",
  "episodes.failed": "فشل تحميل الحلقات",
  "episodes.back": "اضغط رجوع للعودة",

  "player.tuning": "جارٍ الضبط…",
  "player.failed": "فشل التشغيل",
  "player.back": "اضغط رجوع للعودة",
  "player.blocked": "تم منع التشغيل",
  "player.cantLoad": "تعذّر تحميل هذا البث",
};

export const translations: Record<Lang, Dict> = { en, ar };
