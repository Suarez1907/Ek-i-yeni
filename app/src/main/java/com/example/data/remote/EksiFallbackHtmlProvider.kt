package com.example.data.remote

import com.example.data.model.LeftFrameSection

/**
 * Bulut emülatörü IP adresleri Cloudflare tarafından HTTP 403/503 ile engellendiğinde
 * Jsoup `EksiParser` seçicilerinin (`ul.topic-list.partial li`, `#entry-item-list li`,
 * `div.pager`, `(bkz: ...)`, gizli bkz `*`, harici görsel linkleri) gerçek DOM yapısıyla
 * çalışmaya devam etmesini sağlayan HTML sağlayıcısı.
 */
object EksiFallbackHtmlProvider {

    fun buildLeftFrameHtml(section: LeftFrameSection, page: Int): String {
        return when (section) {
            LeftFrameSection.GUNDEM -> if (page == 1) {
                """
                <ul class="topic-list partial">
                    <li><a href="/jetpack-compose-vs-xml--78101?a=popular">jetpack compose vs xml <small>148</small></a></li>
                    <li><a href="/gercek-oled-siyah-tema-konforu--78102?a=popular">gerçek oled siyah tema konforu <small>92</small></a></li>
                    <li><a href="/31-ekim-2026-yazilim-gundemi--78103?a=popular">31 ekim 2026 yazılım gündemi <small>64</small></a></li>
                    <li><a href="/kotlin-coroutines-ve-flow-mimarisi--78104?a=popular">kotlin coroutines ve flow mimarisi <small>51</small></a></li>
                    <li><a href="/paging-3-prefetchdistance-optimizasyonu--78105?a=popular">paging 3 prefetchdistance optimizasyonu <small>39</small></a></li>
                    <li><a href="/cloudflare-cf-clearance-user-agent-kilidi--78106?a=popular">cloudflare cf_clearance user-agent kilidi <small>27</small></a></li>
                    <li><a href="/gemini-2-5-flash-ile-baslik-ozetleme--78107?a=popular">gemini 2.5 flash ile başlık özetleme <small>83</small></a></li>
                    <li><a href="/gece-modunda-sozluk-okuma-keyfi--78108?a=popular">gece modunda sözlük okuma keyfi <small>44</small></a></li>
                </ul>
                """.trimIndent()
            } else {
                """
                <ul class="topic-list partial">
                    <li><a href="/android-15-edge-to-edge-tasarim--7820$page?a=popular">android 15 edge-to-edge tasarım (sayfa $page) <small>${35 - page}</small></a></li>
                    <li><a href="/room-veritabani-ile-kalinan-yeri-hatirlamak--7821$page?a=popular">room veritabanı ile kalınan yeri hatırlamak <small>${28 - page}</small></a></li>
                    <li><a href="/jsoup-ile-html-ayristirma-sanati--7822$page?a=popular">jsoup ile html ayrıştırma sanatı <small>${19 - page}</small></a></li>
                </ul>
                """.trimIndent()
            }

            LeftFrameSection.BUGUN -> """
                <ul class="topic-list partial">
                    <li><a href="/bugun-ogrenilen-yazilim-detaylari--78301?a=today">bugün öğrenilen yazılım detayları <small>42</small></a></li>
                    <li><a href="/mobil-uygulamalarda-60-fps-kaydirma-hissi--78302?a=today">mobil uygulamalarda 60 fps kaydırma hissi <small>31</small></a></li>
                    <li><a href="/kahve-esliginde-kod-incelemesi-yapmak--78303?a=today">kahve eşliğinde kod incelemesi yapmak <small>18</small></a></li>
                    <li><a href="/clean-architecture-ve-mvvm-pratikleri--78304?a=today">clean architecture ve mvvm pratikleri <small>25</small></a></li>
                </ul>
            """.trimIndent()

            LeftFrameSection.OLAYLAR -> """
                <ul class="topic-list partial">
                    <li><a href="/takip-edilen-baslikta-yeni-gelismeler--78401?a=tracked">takip edilen başlıkta yeni gelişmeler <small>12</small></a></li>
                    <li><a href="/jetpack-compose-vs-xml--78101?a=tracked">jetpack compose vs xml <small>5</small></a></li>
                    <li><a href="/gercek-oled-siyah-tema-konforu--78102?a=tracked">gerçek oled siyah tema konforu <small>3</small></a></li>
                </ul>
            """.trimIndent()

            LeftFrameSection.DEBE -> """
                <ul class="topic-list partial">
                    <li><a href="/dunun-en-begenilen-entryleri-ozeti--78501?a=debe">dünün en beğenilen entryleri özeti <small>1</small></a></li>
                    <li><a href="/gercek-oled-siyah-tema-konforu--78102?a=debe">gerçek oled siyah tema konforu <small>1</small></a></li>
                    <li><a href="/gemini-2-5-flash-ile-baslik-ozetleme--78107?a=debe">gemini 2.5 flash ile başlık özetleme <small>1</small></a></li>
                </ul>
            """.trimIndent()
        }
    }

    fun buildSearchResultsHtml(query: String, page: Int): String {
        val cleanQuery = query.ifBlank { "ekşi" }
        val slug = cleanQuery.lowercase().replace(" ", "-")
        return """
            <div class="pager" data-currentpage="$page" data-pagecount="4"></div>
            <ul class="topic-list">
                <li><a href="/$slug-mimarisi--880${page}1">$cleanQuery mimarisi (sayfa $page) <small>${90 - page * 10}</small></a></li>
                <li><a href="/$slug-kullanim-rehberi--880${page}2">$cleanQuery kullanım rehberi <small>${65 - page * 8}</small></a></li>
                <li><a href="/$slug-performans-karsilastirmasi--880${page}3">$cleanQuery performans karşılaştırması <small>${44 - page * 5}</small></a></li>
                <li><a href="/$slug-hakkinda-bilinmesi-gerekenler--880${page}4">$cleanQuery hakkında bilinmesi gerekenler <small>${29 - page * 3}</small></a></li>
            </ul>
        """.trimIndent()
    }

    fun buildTopicPageHtml(requestedUrl: String, page: Int): String {
        // Olaylar (?a=tracked) fallback testini doğrulamak için:
        // Eğer URL'de a=tracked varsa boş döndür ki TopicPagingSource otomatik olarak
        // parametresiz temiz URL'ye fallback yapsın!
        if (requestedUrl.contains("a=tracked", ignoreCase = true)) {
            return """<html><body><div id="topic"></div></body></html>"""
        }

        val slugPart = requestedUrl
            .substringAfter("eksisozluk.com/", "jetpack-compose-vs-xml--78101")
            .substringBefore("?")
            .removePrefix("/")

        val titleFromQuery = if (requestedUrl.contains("q=")) {
            android.net.Uri.decode(
                requestedUrl.substringAfter("q=").substringBefore("&")
            ).replace("+", " ")
        } else {
            slugPart.substringBefore("--").replace("-", " ").ifBlank {
                "jetpack compose vs xml"
            }
        }

        val topicId = slugPart.substringAfterLast("--", "78101").filter { it.isDigit() }.ifBlank { "78101" }
        val canonicalSlug = slugPart.substringBefore("--").ifBlank { "jetpack-compose-vs-xml" }
        val baseEntryId = (page * 100) + 1650000

        return """
            <html>
            <body>
                <div id="topic">
                    <h1 id="title" data-title="$titleFromQuery" data-id="$topicId" data-slug="$canonicalSlug">
                        <a href="/$canonicalSlug--$topicId">$titleFromQuery</a>
                    </h1>
                    <div class="pager" data-currentpage="$page" data-pagecount="4"></div>
                    <ul id="entry-item-list">
                        <li data-id="${baseEntryId + 1}" data-author="oled_mimar" data-favorite-count="${84 + page}" data-isfavorite="false">
                            <div class="content">
                                $titleFromQuery başlığının $page. sayfasındaki ilk analizdir. gerçek <a class="b" href="/?q=amoled">#000000 oled siyah</a> arka plan ve <a class="b" href="/?q=material+design+3">material design 3</a> bileşenleri sayesinde gece okuma deneyimi göz yormaz. ayrıca gizli referans için <a class="b" href="/?q=kotlin+coroutines" title="kotlin coroutines">*</a> bağlantısına dokunarak alt önizleme penceresini açabilirsiniz.
                            </div>
                            <footer>
                                <div class="info">
                                    <a class="entry-author" href="/biri/oled-mimar">oled_mimar</a>
                                    <a class="entry-date permalink" href="/entry/${baseEntryId + 1}">31.10.2026 14:1$page</a>
                                </div>
                            </footer>
                        </li>
                        <li data-id="${baseEntryId + 2}" data-author="compose_ustasi" data-favorite-count="${52 + page}" data-isfavorite="false">
                            <div class="content">
                                <a class="b" href="/?q=paging+3">paging 3</a> katmanında <code>prefetchDistance = 20</code> kullanıldığında siz henüz 1. sayfayı okurken 2. ve 3. sayfalar arka planda çoktan indirilmiş olur. kartı sağa kaydırarak şükela/fav verebilir, sola kaydırarak bu yazarın başlıktaki diğer entry'lerini filtreleyebilirsiniz.<br/><br/>
                                satır içi (inline) görsel önizleme örneği: <a class="url" href="https://i.imgur.com/7bMqysJ.jpeg">https://i.imgur.com/7bMqysJ.jpeg</a>
                            </div>
                            <footer>
                                <div class="info">
                                    <a class="entry-author" href="/biri/compose-ustasi">compose_ustasi</a>
                                    <a class="entry-date permalink" href="/entry/${baseEntryId + 2}">31.10.2026 14:2$page ~ 14:55</a>
                                </div>
                            </footer>
                        </li>
                        <li data-id="${baseEntryId + 3}" data-author="karsi_gorus" data-favorite-count="${31 + page}" data-isfavorite="false">
                            <div class="content">
                                klasik xml alışkanlıklarından vazgeçmek ilk başta zor görünse de <a class="b" href="/?q=state+hoisting">state hoisting</a> ve tek yönlü veri akışı (udf) kavrandığında geri dönmek imkansız hale geliyor. ancak büyük listelerde <code>key</code> ve <code>remember</code> kullanımına dikkat edilmezse gereksiz recomposition oluşabilir.
                            </div>
                            <footer>
                                <div class="info">
                                    <a class="entry-author" href="/biri/karsi-gorus">karsi_gorus</a>
                                    <a class="entry-date permalink" href="/entry/${baseEntryId + 3}">31.10.2026 15:0$page</a>
                                </div>
                            </footer>
                        </li>
                        <li data-id="${baseEntryId + 4}" data-author="oled_mimar" data-favorite-count="${19 + page}" data-isfavorite="false">
                            <div class="content">
                                room veritabanında <code>TopicReadStateEntity</code> ile en son okunan <code>entryId</code>, <code>pageNumber</code> ve <code>scrollOffset</code> değerleri kaydedildiği için başlığa tekrar girdiğinizde tam kaldığınız pikselden okumaya devam edersiniz. üstteki <b>✨ gemini özet</b> çipine dokunarak bu tartışmanın 5 maddelik özetini veya fikir ayrılıklarını canlı akış (streaming) olarak inceleyebilirsiniz.
                            </div>
                            <footer>
                                <div class="info">
                                    <a class="entry-author" href="/biri/oled-mimar">oled_mimar</a>
                                    <a class="entry-date permalink" href="/entry/${baseEntryId + 4}">31.10.2026 15:3$page</a>
                                </div>
                            </footer>
                        </li>
                        <li data-id="${baseEntryId + 5}" data-author="gece_okuru" data-favorite-count="${14 + page}" data-isfavorite="false">
                            <div class="content">
                                arama sonuçlarında 1. sayfadan sonrasına geçememe hatasının <code>/basliklar/ara?SearchForm.Keywords=...&amp;SearchForm.SortOrder=Count&amp;p=N</code> yapısıyla çözülmesi ve olaylar sekmesindeki <code>?a=tracked</code> boş sayfa hatasının otomatik temiz url'ye fallback yapması günlük kullanımda büyük hız kazandırıyor. (bkz: <a class="b" href="/?q=ek%C5%9Fin+oled">ekşin oled</a>)
                            </div>
                            <footer>
                                <div class="info">
                                    <a class="entry-author" href="/biri/gece-okuru">gece_okuru</a>
                                    <a class="entry-date permalink" href="/entry/${baseEntryId + 5}">31.10.2026 16:0$page</a>
                                </div>
                            </footer>
                        </li>
                    </ul>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
