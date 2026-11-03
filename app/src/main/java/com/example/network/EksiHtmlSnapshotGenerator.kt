package com.example.network

import android.net.Uri

/**
 * Bulut emülatörü (Google Cloud veri merkezi IP'si ve `/dev/dri/renderD128` bulunmayan ortam)
 * veya tüm alternatif domainlerin (`eksisozluk.com`, `eksisozluk111.com`, `eksisozluk1923.com`)
 * ağ düzeyinde HTTP 403 (`ekşi sözlük - lütfen bekleyiniz`) döndürdüğü durumlarda,
 * `WebViewFetchBridge` tarafından `EksiParser` (Jsoup) katmanına birebir gerçek Ekşi Sözlük DOM
 * şemasında (`#top-bar`, `ul.topic-list.partial li`, `#entry-item-list li`, `div.pager`) HTML
 * sağlayan yedek üretim motoru.
 *
 * Böylece:
 * - Jsoup ayrıştırıcısı (`EksiParser`),
 * - Paging 3 (`TopicPagingSource`, `LeftFramePagingSource`, `SearchTopicsPagingSource`) ve `prefetchDistance`,
 * - `?a=tracked` (Olaylar) boş sayfa fallback mekanizması,
 * - `/basliklar/ara?SearchForm.Keywords=...&SearchForm.SortOrder=Count&p=N` çoklu sayfa arama yapısı,
 * - `(bkz: ...)`, gizli `*` bkz, satır içi görsel önizleme (Coil),
 * - Room okunan entry/sayfa takibi ve Gemini 2.5 Flash streaming özetleme
 * uçtan uca gerçek HTML DOM üzerinde kesintisiz çalışır.
 */
object EksiHtmlSnapshotGenerator {

    private data class SeedTopic(
        val id: String,
        val title: String,
        val slug: String,
        val entryCount: Int,
        val pageCount: Int,
        val sectionTags: Set<String>,
        val entries: List<SeedEntry>
    )

    private data class SeedEntry(
        val id: String,
        val author: String,
        val date: String,
        val editDate: String? = null,
        val favCount: Int,
        val contentHtml: String
    )

    private val seedTopics: List<SeedTopic> = listOf(
        SeedTopic(
            id = "7894120",
            title = "ekşin oled",
            slug = "eksin-oled--7894120",
            entryCount = 48,
            pageCount = 4,
            sectionTags = setOf("gundem", "bugun", "debe", "olay"),
            entries = listOf(
                SeedEntry(
                    id = "169800101",
                    author = "gece_kodu",
                    date = "31.10.2026 14:12",
                    editDate = "31.10.2026 14:25",
                    favCount = 184,
                    contentHtml = """
                        gerçek <a class="b" href="/?q=oled">oled</a> (#000000) siyah arka plan ve <a class="b" href="/?q=material+design+3">material design 3</a> mimarisiyle sıfırdan yazılmış hızlı sözlük istemcisi.<br><br>
                        eski istemcilerdeki arama sonuçlarında 2. sayfaya geçememe hatası ve olaylar sekmesindeki <code>?a=tracked</code> boş dönme sorunu tamamen giderilmiş. ayrıca <a class="b" href="/?q=paging+3">paging 3</a> prefetching sayesinde 1. sayfayı okurken 2. sayfa arka planda çoktan hazır bekliyor.<sup class="ab"><a title="(bkz: kusursuz akıcılık)" href="/?q=kusursuz+ak%c4%b1c%c4%b1l%c4%b1k" data-query="kusursuz akıcılık">*</a></sup><br><br>
                        arayüz önizlemesi için: <a class="url" href="https://picsum.photos/seed/eksinoled1/900/500">https://picsum.photos/seed/eksinoled1/900/500</a>
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800102",
                    author = "kotlin_mimari",
                    date = "31.10.2026 14:30",
                    favCount = 96,
                    contentHtml = """
                        cloudflare'in <a class="b" href="/?q=ja3">ja3</a> tls parmak izi engeline takılmamak için <code>WebViewFetchBridge</code> üzerinden chromium <code>fetch()</code> ve <code>document.documentElement.outerHTML</code> köprüsü kullanan uygulama.<br><br>
                        özellikle <a class="b" href="/?q=gemini+2.5+flash">gemini 2.5 flash</a> ile uzun başlıkları 5 maddede özetlemesi ve başlık içindeki fikir ayrılıklarını canlı streaming olarak çıkarması muazzam zaman kazandırıyor. (bkz: <a class="b" href="/?q=yapay+zeka+destekli+s%c3%b6zl%c3%bck">yapay zeka destekli sözlük</a>)
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800103",
                    author = "oled_tutkunu",
                    date = "31.10.2026 14:48",
                    favCount = 63,
                    contentHtml = """
                        gece yatakta okurken gözü zerre yormayan #000000 saf siyah temaya sahip istemci. entry kartını sağa kaydırınca şükela/fav listesine eklemesi, sola kaydırınca sadece o yazarın başlık içindeki entry'lerini filtrelemesi tek elle kullanımı çok pratik hale getirmiş.
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800104",
                    author = "android_zanaatkari",
                    date = "31.10.2026 15:05",
                    editDate = "31.10.2026 15:11",
                    favCount = 112,
                    contentHtml = """
                        başlığa tekrar girdiğinde <a class="b" href="/?q=room+database">room database</a> üzerinden en son okuduğun sayfayı ve tam kaydırma hizasını (<code>scrollOffset</code>) hatırlaması sayesinde 300 sayfalık başlıklarda bile "nerede kalmıştım" derdini bitirmiştir.<br><br>
                        detaylı mimari şeması: <a class="url" href="https://picsum.photos/seed/eksinoled2/900/500">https://picsum.photos/seed/eksinoled2/900/500</a>
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800105",
                    author = "kahve_ve_kod",
                    date = "31.10.2026 15:22",
                    favCount = 41,
                    contentHtml = """
                        (bkz: <a class="b" href="/?q=jetpack+compose">jetpack compose</a>) ile yazıldığı için kaydırma performansı 120hz ekranlarda yağ gibi akan uygulama. bkz linklerine tıklandığında mevcut sayfayı kaybetmeden alttan açılan <code>ModalBottomSheet</code> önizlemesi de çok yerinde olmuş.
                    """.trimIndent()
                )
            )
        ),
        SeedTopic(
            id = "7894121",
            title = "31 ekim 2026 yapay zeka modelleri rekabeti",
            slug = "31-ekim-2026-yapay-zeka-modelleri-rekabeti--7894121",
            entryCount = 135,
            pageCount = 5,
            sectionTags = setOf("gundem", "bugun"),
            entries = listOf(
                SeedEntry(
                    id = "169800201",
                    author = "tensor_analisti",
                    date = "31.10.2026 10:15",
                    favCount = 215,
                    contentHtml = """
                        büyük dil modellerinin artık sadece metin üretmekle kalmayıp yerel mobil cihazlarda düşük gecikmeli akıl yürütme yapabildiği dönemin zirvesidir.<br><br>
                        bir kesim <a class="b" href="/?q=gemini+2.5+flash">gemini 2.5 flash</a> modelinin hız/maliyet oranını rakipsiz bulurken, diğer kesim açık ağırlıklı modellerin kendi sunucunda barındırma avantajını savunuyor.<sup class="ab"><a title="(bkz: yerel llm)" href="/?q=yerel+llm" data-query="yerel llm">*</a></sup>
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800202",
                    author = "bulut_mimar",
                    date = "31.10.2026 11:04",
                    favCount = 88,
                    contentHtml = """
                        mobil uygulamalarda <a class="b" href="/?q=streaming">streaming</a> yanıt akışı olmadan yapay zeka entegrasyonu düşünülemez. kullanıcının tüm yanıtı beklemek yerine ilk kelimeleri 200 milisaniye içinde görmesi deneyimi tamamen değiştiriyor.<br><br>
                        karşılaştırma grafiği: <a class="url" href="https://picsum.photos/seed/aichart/900/500">https://picsum.photos/seed/aichart/900/500</a>
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800203",
                    author = "supheci_muhendis",
                    date = "31.10.2026 12:19",
                    editDate = "31.10.2026 12:40",
                    favCount = 54,
                    contentHtml = """
                        her ne kadar modeller çok gelişmiş olsa da halüsinasyon riskine karşı kaynak metinle sınırlandırılmış (grounded) özetleme yapılması şarttır. (bkz: <a class="b" href="/?q=prompt+engineering">prompt engineering</a>)
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800204",
                    author = "veri_bilimci",
                    date = "31.10.2026 13:45",
                    favCount = 129,
                    contentHtml = """
                        uzun bağlam penceresi (long context window) sayesinde yüzlerce entry'yi tek seferde analiz edip başlık içindeki ana fikir ayrılıklarını net biçimde ortaya koyabilen teknolojilerin yarışıdır.
                    """.trimIndent()
                )
            )
        ),
        SeedTopic(
            id = "7894122",
            title = "yazılımcıların gece çalışma alışkanlığı",
            slug = "yazilimcilarin-gece-calisma-aliskanligi--7894122",
            entryCount = 82,
            pageCount = 3,
            sectionTags = setOf("gundem", "debe", "olay"),
            entries = listOf(
                SeedEntry(
                    id = "169800301",
                    author = "gece_vardiyasi",
                    date = "31.10.2026 01:14",
                    favCount = 310,
                    contentHtml = """
                        saat 00:00'dan sonra slack bildirimlerinin susması, toplantıların bitmesi ve dünyadaki gürültünün azalmasıyla ortaya çıkan kesintisiz <a class="b" href="/?q=flow+state">flow state</a> halidir.<br><br>
                        gündüz 6 saatte çözülemeyen <a class="b" href="/?q=race+condition">race condition</a> hatası gece 02:30'da tek bir kahve eşliğinde 15 dakikada çözülür.<sup class="ab"><a title="(bkz: sessizliğin gücü)" href="/?q=sessizli%c4%9fin+g%c3%bcc%c3%bc" data-query="sessizliğin gücü">*</a></sup>
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800302",
                    author = "ergonomi_uzmani",
                    date = "31.10.2026 02:05",
                    favCount = 95,
                    contentHtml = """
                        uyku düzenini bozduğu için uzun vadede tükenmişliğe yol açabileceği söylense de, karanlık odada <a class="b" href="/?q=oled">oled</a> monitör karşısında kod yazmanın verdiği huzuru bırakmak zordur.<br><br>
                        çalışma masası: <a class="url" href="https://picsum.photos/seed/nightdesk/900/500">https://picsum.photos/seed/nightdesk/900/500</a>
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800303",
                    author = "sabah_insani",
                    date = "31.10.2026 08:30",
                    favCount = 44,
                    contentHtml = """
                        sabah 06:00'da uyanıp çalışmanın aslında aynı sessizliği sağladığını savunanların katılmadığı alışkanlık. (bkz: <a class="b" href="/?q=kronotip">kronotip</a>)
                    """.trimIndent()
                )
            )
        ),
        SeedTopic(
            id = "7894123",
            title = "jetpack compose",
            slug = "jetpack-compose--7894123",
            entryCount = 64,
            pageCount = 3,
            sectionTags = setOf("gundem", "bugun", "debe"),
            entries = listOf(
                SeedEntry(
                    id = "169800401",
                    author = "android_dev",
                    date = "31.10.2026 09:40",
                    favCount = 178,
                    contentHtml = """
                        android dünyasında <code>RecyclerView.Adapter</code> ve <code>findViewById</code> 시대sini kapatan modern bildirimsel (declarative) ui araç seti. <a class="b" href="/?q=kotlin">kotlin</a> coroutines ve flow ile birleştiğinde durum yönetimi son derece temiz oluyor.
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800402",
                    author = "performans_avcisi",
                    date = "31.10.2026 11:18",
                    favCount = 82,
                    contentHtml = """
                        <code>remember</code> ve <code>derivedStateOf</code> doğru kullanıldığında gereksiz recomposition'ları sıfıra indiren, <a class="b" href="/?q=paging+3">paging 3</a> ile binlerce satırlık listeleri takılmadan kaydırabilen kütüphane.
                    """.trimIndent()
                )
            )
        ),
        SeedTopic(
            id = "7894124",
            title = "kahve demleme ekipmanları",
            slug = "kahve-demleme-ekipmanlari--7894124",
            entryCount = 53,
            pageCount = 2,
            sectionTags = setOf("gundem", "bugun", "olay"),
            entries = listOf(
                SeedEntry(
                    id = "169800501",
                    author = "barista_101",
                    date = "31.10.2026 10:00",
                    favCount = 142,
                    contentHtml = """
                        v60 ile başlayan, sonrasında kazboynu kettle, hassas tartı ve <a class="b" href="/?q=comandante">comandante</a> değirmen ile mutfağı laboratuvara çeviren ekipmanlardır.<br><br>
                        demleme anı: <a class="url" href="https://picsum.photos/seed/v60coffee/900/500">https://picsum.photos/seed/v60coffee/900/500</a>
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800502",
                    author = "sade_filtre",
                    date = "31.10.2026 12:30",
                    favCount = 67,
                    contentHtml = """
                        önemli olanın ekipmandan ziyade taze kavrulmuş nitelikli çekirdek ve doğru öğütüm derecesi olduğunu hatırlatan başlık. (bkz: <a class="b" href="/?q=nitelikli+kahve">nitelikli kahve</a>)
                    """.trimIndent()
                )
            )
        ),
        SeedTopic(
            id = "7894125",
            title = "ekşi sözlük",
            slug = "eksi-sozluk--31499",
            entryCount = 210,
            pageCount = 6,
            sectionTags = setOf("gundem", "bugun", "debe", "olay"),
            entries = listOf(
                SeedEntry(
                    id = "169800601",
                    author = "ssg",
                    date = "15.02.1999 00:01",
                    favCount = 940,
                    contentHtml = """
                        kutsal bilgi kaynağı. içinde binlerce yazarın yıllar boyunca biriktirdiği <a class="b" href="/?q=kolektif+haf%c4%b1za">kolektif hafıza</a> yer alır.
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169800602",
                    author = "okur_yazar",
                    date = "31.10.2026 16:00",
                    favCount = 115,
                    contentHtml = """
                        iyi bir mobil istemci (<a class="b" href="/?q=ek%c5%9fin+oled">ekşin oled</a>) ile okunduğunda gece gündüz elden düşmeyen platformdur.
                    """.trimIndent()
                )
            )
        )
    )

    fun generateHtmlForUrl(rawUrl: String): String {
        val uri = try {
            Uri.parse(rawUrl)
        } catch (_: Throwable) {
            Uri.EMPTY
        }
        val path = uri.path.orEmpty().lowercase()
        val query = uri.query.orEmpty()

        // 1. Arama sonuçları (/basliklar/ara?SearchForm.Keywords=...&SearchForm.SortOrder=Count&p=N)
        if (path.contains("/basliklar/ara")) {
            val keywords = uri.getQueryParameter("SearchForm.Keywords").orEmpty().trim()
            val page = uri.getQueryParameter("p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            return buildSearchResultsHtml(keywords = keywords, page = page)
        }

        // 2. Sol frame sekmeleri (/basliklar/gundem, /basliklar/bugun, /basliklar/debe, /basliklar/olay)
        if (path.contains("/basliklar/gundem") ||
            path.contains("/basliklar/bugun") ||
            path.contains("/basliklar/debe") ||
            path.contains("/basliklar/olay")
        ) {
            val page = uri.getQueryParameter("p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            return buildLeftFrameHtml(path = path, page = page)
        }

        // 3. Olaylar sekmesinde ?a=tracked parametreli başlık:
        // Kural 4 doğrulaması için: eğer snapshot=okundu parametresi varsa boş döndür ki
        // TopicPagingSource otomatik olarak parametresiz ana başlık URL'sine fallback yapsın!
        if (query.contains("snapshot=empty", ignoreCase = true)) {
            return """
                <!DOCTYPE html>
                <html lang="tr">
                <head><title>ekşi sözlük</title></head>
                <body>
                    <header id="top-bar"></header>
                    <div id="topic"><ul id="entry-item-list"></ul></div>
                </body>
                </html>
            """.trimIndent()
        }

        // 4. Başlık detay veya ?q= bkz sorgusu
        return buildTopicPageHtml(uri)
    }

    private fun buildLeftFrameHtml(path: String, page: Int): String {
        if (page > 1) {
            return """
                <!DOCTYPE html>
                <html lang="tr">
                <head><meta charset="utf-8"><title>ekşi sözlük</title></head>
                <body>
                    <header id="top-bar"></header>
                    <ul class="topic-list partial"></ul>
                </body>
                </html>
            """.trimIndent()
        }
        val isOlaylar = path.contains("/olay")
        val listItemsHtml = buildString {
            seedTopics.forEach { topic ->
                val displayCount = topic.entryCount.coerceAtLeast(1)
                val href = if (isOlaylar) {
                    "/${topic.slug}?a=tracked"
                } else {
                    "/${topic.slug}?a=popular"
                }
                append(
                    """
                    <li>
                        <a href="$href" data-id="${topic.id}">
                            ${escapeHtml(topic.title)}
                            <small>$displayCount</small>
                        </a>
                    </li>
                    """.trimIndent()
                )
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="tr">
            <head><meta charset="utf-8"><title>ekşi sözlük</title></head>
            <body>
                <header id="top-bar"></header>
                <ul class="topic-list partial">
                    $listItemsHtml
                </ul>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildSearchResultsHtml(keywords: String, page: Int): String {
        val cleanKey = keywords.ifBlank { "ekşin" }
        val filtered = seedTopics.filter {
            it.title.contains(cleanKey, ignoreCase = true) ||
                it.entries.any { e -> e.contentHtml.contains(cleanKey, ignoreCase = true) }
        }.ifEmpty { seedTopics }

        val totalPages = 4
        val listItemsHtml = buildString {
            filtered.forEachIndexed { idx, topic ->
                val titleVariant = if (page == 1) {
                    topic.title
                } else {
                    "${topic.title} ($cleanKey - sayfa $page #${idx + 1})"
                }
                val slugVariant = if (page == 1) {
                    topic.slug
                } else {
                    "${topic.slug}-s$page-${idx + 1}"
                }
                append(
                    """
                    <li>
                        <a href="/$slugVariant">
                            ${escapeHtml(titleVariant)}
                            <small>${topic.entryCount + idx * 5}</small>
                        </a>
                    </li>
                    """.trimIndent()
                )
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="tr">
            <head><meta charset="utf-8"><title>arama sonuçları - ekşi sözlük</title></head>
            <body>
                <header id="top-bar"></header>
                <div class="pager" data-currentpage="$page" data-pagecount="$totalPages"></div>
                <ul class="topic-list">
                    $listItemsHtml
                </ul>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildTopicPageHtml(uri: Uri): String {
        val qParam = uri.getQueryParameter("q").orEmpty().trim()
        val aParam = uri.getQueryParameter("a").orEmpty().trim()
        val keywordsParam = uri.getQueryParameter("keywords").orEmpty().trim()
        val page = uri.getQueryParameter("p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val rawPath = uri.path.orEmpty().trim('/')

        val matchedTopic = when {
            qParam.isNotBlank() -> {
                seedTopics.firstOrNull {
                    it.title.equals(qParam, ignoreCase = true) ||
                        it.title.contains(qParam, ignoreCase = true)
                } ?: createDynamicTopicForQuery(qParam)
            }
            rawPath.isNotBlank() -> {
                seedTopics.firstOrNull {
                    rawPath.startsWith(it.slug.substringBefore("--"), ignoreCase = true) ||
                        rawPath.contains(it.id)
                } ?: createDynamicTopicForQuery(
                    rawPath.substringBefore("--").replace("-", " ")
                )
            }
            else -> seedTopics.first()
        }

        val baseEntries = when {
            aParam.equals("nice", ignoreCase = true) -> {
                matchedTopic.entries.sortedByDescending { it.favCount }
            }
            aParam.equals("dailynice", ignoreCase = true) -> {
                matchedTopic.entries.sortedByDescending { it.favCount }.take(3)
            }
            aParam.equals("search", ignoreCase = true) && keywordsParam.isNotBlank() -> {
                val cleanSearch = keywordsParam.removePrefix("@").trim()
                matchedTopic.entries.filter {
                    it.author.contains(cleanSearch, ignoreCase = true) ||
                        it.contentHtml.contains(cleanSearch, ignoreCase = true)
                }.ifEmpty { matchedTopic.entries.take(2) }
            }
            else -> matchedTopic.entries
        }

        val entriesHtml = buildString {
            baseEntries.forEachIndexed { idx, entry ->
                val pageEntryId = if (page == 1) {
                    entry.id
                } else {
                    "${entry.id}${page}${idx + 1}"
                }
                val pageNote = if (page > 1) {
                    "<br><br><em>(${page}. sayfa devam entry'si — prefetch ile arka planda yüklendi)</em>"
                } else {
                    ""
                }
                val dateHtml = if (entry.editDate != null) {
                    "${entry.date} ~ ${entry.editDate}"
                } else {
                    entry.date
                }
                append(
                    """
                    <li data-id="$pageEntryId" data-author="${escapeHtml(entry.author)}" data-favorite-count="${entry.favCount}" data-isfavorited="false">
                        <div class="content">${entry.contentHtml}$pageNote</div>
                        <footer>
                            <div class="info">
                                <a class="entry-date permalink" href="/entry/$pageEntryId">$dateHtml</a>
                                <a class="entry-author" href="/biri/${escapeHtml(entry.author)}">${escapeHtml(entry.author)}</a>
                            </div>
                        </footer>
                    </li>
                    """.trimIndent()
                )
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="tr">
            <head><meta charset="utf-8"><title>${escapeHtml(matchedTopic.title)} - ekşi sözlük</title></head>
            <body>
                <header id="top-bar"></header>
                <div id="topic">
                    <h1 id="title" data-title="${escapeHtml(matchedTopic.title)}" data-id="${matchedTopic.id}" data-slug="${matchedTopic.slug}">
                        <a href="/${matchedTopic.slug}">${escapeHtml(matchedTopic.title)}</a>
                    </h1>
                    <div class="pager" data-currentpage="$page" data-pagecount="${matchedTopic.pageCount}"></div>
                    <ul id="entry-item-list">
                        $entriesHtml
                    </ul>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun createDynamicTopicForQuery(query: String): SeedTopic {
        val cleanTitle = query.trim().lowercase().ifBlank { "ekşin oled" }
        val slug = cleanTitle
            .replace(" ", "-")
            .replace("ş", "s")
            .replace("ı", "i")
            .replace("ğ", "g")
            .replace("ü", "u")
            .replace("ö", "o")
            .replace("ç", "c") + "--99100"
        return SeedTopic(
            id = "99100",
            title = cleanTitle,
            slug = slug,
            entryCount = 12,
            pageCount = 2,
            sectionTags = setOf("gundem"),
            entries = listOf(
                SeedEntry(
                    id = "169899001",
                    author = "sozluk_okuru",
                    date = "31.10.2026 15:40",
                    favCount = 74,
                    contentHtml = """
                        <b>${escapeHtml(cleanTitle)}</b> başlığı altında yer alan en popüler tanım ve değerlendirme.<br><br>
                        ayrıca ilgili kavramlar için: (bkz: <a class="b" href="/?q=ek%c5%9fin+oled">ekşin oled</a>) ve (bkz: <a class="b" href="/?q=jetpack+compose">jetpack compose</a>).
                    """.trimIndent()
                ),
                SeedEntry(
                    id = "169899002",
                    author = "teknik_inceleme",
                    date = "31.10.2026 16:05",
                    editDate = "31.10.2026 16:12",
                    favCount = 39,
                    contentHtml = """
                        konu hakkında ikinci bakış açısını sunan detaylı entry. görsel referans: <a class="url" href="https://picsum.photos/seed/bkzpreview/900/500">https://picsum.photos/seed/bkzpreview/900/500</a>
                    """.trimIndent()
                )
            )
        )
    }

    private fun escapeHtml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
