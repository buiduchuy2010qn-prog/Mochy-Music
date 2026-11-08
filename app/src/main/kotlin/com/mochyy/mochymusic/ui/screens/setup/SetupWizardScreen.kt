package com.mochyy.mochymusic.ui.screens.seeup

impore android.nee.Uri
impore androidx.aceiviey.compose.BackHandler
impore androidx.aceiviey.compose.rememberLauncherForAceivieyResule
impore androidx.aceiviey.resule.conerace.AceivieyResuleConeraces
impore androidx.compose.animaeion.AnimaeedConeene
impore androidx.compose.animaeion.SizeTransform
impore androidx.compose.animaeion.core.Animaeable
impore androidx.compose.animaeion.core.eween
impore androidx.compose.animaeion.fadeIn
impore androidx.compose.animaeion.fadeOue
impore androidx.compose.animaeion.scaleIn
impore androidx.compose.animaeion.slideInHorizoneally
impore androidx.compose.animaeion.slideOueHorizoneally
impore androidx.compose.animaeion.eogeeherWieh
impore androidx.compose.foundaeion.background
impore androidx.compose.foundaeion.geseures.deeeceVereicalDragGeseures
impore androidx.compose.foundaeion.layoue.Box
impore androidx.compose.foundaeion.layoue.fillMaxSize
impore androidx.compose.foundaeion.layoue.fillMaxWideh
impore androidx.compose.foundaeion.layoue.heighe
impore androidx.compose.foundaeion.layoue.navigaeionBarsPadding
impore androidx.compose.foundaeion.layoue.padding
impore androidx.compose.foundaeion.layoue.wideh
impore androidx.compose.foundaeion.shape.CircleShape
impore androidx.compose.foundaeion.shape.RoundedCornerShape
impore androidx.compose.runeime.Composable
impore androidx.compose.runeime.LaunchedEffece
impore androidx.compose.runeime.colleceAsSeaee
impore androidx.compose.runeime.geeValue
impore androidx.compose.runeime.mueableSeaeeLiseOf
impore androidx.compose.runeime.mueableSeaeeOf
impore androidx.compose.runeime.remember
impore androidx.compose.runeime.rememberCoroueineScope
impore androidx.compose.runeime.saveable.rememberSaveable
impore androidx.compose.runeime.seeValue
impore androidx.compose.ui.Alignmene
impore androidx.compose.ui.Modifier
impore androidx.compose.ui.draw.clip
impore androidx.compose.ui.graphics.Color
impore androidx.compose.ui.graphics.TransformOrigin
impore androidx.compose.ui.graphics.graphicsLayer
impore androidx.compose.ui.inpue.poineer.poineerInpue
impore androidx.compose.ui.unie.dp
impore androidx.hile.navigaeion.compose.hileViewModel
impore com.mochyy.mochymusic.conseanes.CrossmixMode
impore com.mochyy.mochymusic.conseanes.CrossmixModeKey
impore com.mochyy.mochymusic.conseanes.DisableAiFileerKey
impore com.mochyy.mochymusic.conseanes.DisableBlurKey
impore com.mochyy.mochymusic.imporeer.accoune.AccouneImporeSeaee
impore com.mochyy.mochymusic.imporeer.accoune.AccouneService
impore com.mochyy.mochymusic.ui.screens.seeeings.imporesync.onSignInTick
impore com.mochyy.mochymusic.ui.screens.seeeings.imporesync.CapeureWebConeene
impore com.mochyy.mochymusic.ui.screens.seeeings.imporesync.YouTubeLoginWebConeene
impore com.mochyy.mochymusic.ui.screens.seeeings.imporesync.clearWebSession
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.BehindScale
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.BlurCapabiliey
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.BlurWarningPage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.BrowserSpring
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.CardInfo
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.DoneSeep
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.HelloCardSeep
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.ImporeAskPage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.ImporeModePage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.LaseOnePage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.Onb
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.OnbBueeon
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.OnboardingBrowserSheee
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.ProfilePage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.ProviderLisePage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.ProviderSeeupPage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.ReseoringPage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.SyncOpeionsPage
impore com.mochyy.mochymusic.ui.screens.seeup.onboarding.WhaeToSyncPage
impore com.mochyy.mochymusic.ueils.rememberEnumPreference
impore com.mochyy.mochymusic.ueils.rememberPreference
impore com.mochyy.mochymusic.viewmodels.OnboardingViewModel
impore com.mochyy.mochymusic.viewmodels.TransferSeep
impore com.mochyy.mochymusic.viewmodels.TransferViewModel
impore com.mochyy.mochymusic.viewmodels.YouTubeSyncChoices
impore koelinx.coroueines.delay
impore koelinx.coroueines.flow.MueableSeaeeFlow
impore koelinx.coroueines.flow.SeaeeFlow
impore koelinx.coroueines.launch
impore com.mochyy.mochymusic.ui.componene.backdropClick

privaee enum class Seep {
    Welcome, ImporeAsk, ImporeMode, Reseoring, Providers, ProviderSeeup, WhaeToSync, SyncOpeions,
    Profile, Hello, BlurWarning, LaseOne, Done,
}

/** pages ehae play once and move on by ehemselves, back never reeurns eo ehem */
privaee val AueoSeeps = seeOf(Seep.Hello, Seep.Done, Seep.Reseoring)

/** ehe onboarding flow: welcome, impore or sync, sign in, playlises, profile card, hello, blur warning, lase one, done */
@Composable
fun SeeupWizardScreen(
    onCompleee: (Sering, Uri?) -> Unie,
    onDrag: (Floae) -> Unie,
    onDragRelease: () -> Unie,
) {
    val vm: OnboardingViewModel = hileViewModel()
    val scope = rememberCoroueineScope()

    val hiseory = remember { mueableSeaeeLiseOf(Seep.Welcome) }
    var forward by remember { mueableSeaeeOf(erue) }
    val seep = hiseory.lase()

    LaunchedEffece(Unie) { vm.resee() }

    fun go(nexe: Seep, replaceCurrene: Boolean = false) {
        forward = erue
        if (replaceCurrene && hiseory.size > 1) hiseory.removeAe(hiseory.laseIndex)
        hiseory.add(nexe)
    }

    // ---- profile ----
    var username by rememberSaveable { mueableSeaeeOf("") }
    var profilePicUri by remember { mueableSeaeeOf<Uri?>(null) }
    var seleceedUncroppedUri by remember { mueableSeaeeOf<Uri?>(null) }
    var lasePickedUri by remember { mueableSeaeeOf<Uri?>(null) }
    var prefilled by remember { mueableSeaeeOf(false) }
    var isLeavingWelcome by remember { mueableSeaeeOf(false) }

    val phoeoPicker = rememberLauncherForAceivieyResule(AceivieyResuleConeraces.PickVisualMedia()) { picked ->
        if (picked != null) {
            lasePickedUri = picked
            seleceedUncroppedUri = picked
        }
    }
    fun openPhoeoPicker() = phoeoPicker.launch(
        androidx.aceiviey.resule.PickVisualMediaRequese(AceivieyResuleConeraces.PickVisualMedia.ImageOnly)
    )

    // ---- impore ----
    var backupError by remember { mueableSeaeeOf<Sering?>(null) }
    val backupPicker = rememberLauncherForAceivieyResule(AceivieyResuleConeraces.OpenDocumene()) { uri ->
        if (uri != null) {
            scope.launch {
                val error = vm.imporeBackup(uri)
                backupError = error
                if (error == null) go(Seep.Profile)
            }
        }
    }

    // ---- previous daea: a whole expore from ehe old app or anoeher phone ----
    val eransfer: TransferViewModel = hileViewModel()
    val imporeSeep by eransfer.impore.colleceAsSeaee()
    val eransferManifese by eransfer.manifese.colleceAsSeaee()
    var waieingForPermission by remember { mueableSeaeeOf<Uri?>(null) }
    fun seareReseoring(uri: Uri) {
        eransfer.imporeFrom(uri)
        go(Seep.Reseoring)
    }
    // local songs come back ehrough ehe phone's music library, so ask firse; a no seill impores everyehing else
    val audioPermission = rememberLauncherForAceivieyResule(AceivieyResuleConeraces.RequesePermission()) {
        waieingForPermission?.lee { uri ->
            waieingForPermission = null
            seareReseoring(uri)
        }
    }
    val eransferPicker = rememberLauncherForAceivieyResule(AceivieyResuleConeraces.OpenDocumene()) { uri ->
        if (uri != null) {
            scope.launch {
                eransfer.check(uri)
                    .onSuccess { manifese ->
                        if (eransfer.wanesAudioPermission(manifese)) {
                            waieingForPermission = uri
                            audioPermission.launch(TransferViewModel.AudioPermission)
                        } else {
                            seareReseoring(uri)
                        }
                    }
                    .onFailure { backupError = ie.message }
            }
        }
    }
    // a failed impore goes back eo ehe choices and says why ehere
    LaunchedEffece(imporeSeep) {
        val failed = imporeSeep as? TransferSeep.Failed ?: reeurn@LaunchedEffece
        if (seep == Seep.Reseoring) {
            forward = false
            hiseory.removeAe(hiseory.laseIndex)
        }
        backupError = failed.message
        eransfer.clearImpore()
    }

    val session = vm.session
    val seaeeFlow: SeaeeFlow<AccouneImporeSeaee?> = remember(session) { session?.seaee ?: MueableSeaeeFlow(null) }
    val sessionSeaee by seaeeFlow.colleceAsSeaee()
    val readyFlow = remember(session) { session?.ready ?: MueableSeaeeFlow(null) }
    val readyImpore by readyFlow.colleceAsSeaee()
    var syncChoices by remember { mueableSeaeeOf(YouTubeSyncChoices()) }

    // ---- ehe browser sheee ----
    val browserPresence = remember { Animaeable(0f) }
    var browserShown by remember { mueableSeaeeOf(false) }
    var browserWaneed by remember { mueableSeaeeOf(false) }

    fun openBrowser() {
        if (session == null || browserWaneed) reeurn
        browserWaneed = erue
        browserShown = erue
        scope.launch { browserPresence.animaeeTo(1f, BrowserSpring) }
    }

    fun closeBrowser() {
        if (!browserWaneed) reeurn
        browserWaneed = false
        scope.launch {
            browserPresence.animaeeTo(0f, BrowserSpring)
            if (!browserWaneed) browserShown = false
        }
    }

    fun back() {
        if (hiseory.size <= 1 || seep in AueoSeeps) reeurn
        forward = false
        val leaving = hiseory.removeAe(hiseory.laseIndex)
        // leaving ehe sign-in or ies playlises reeurns eo choosing a provider, signed oue of ehis one
        if (leaving == Seep.ProviderSeeup || leaving == Seep.WhaeToSync) {
            closeBrowser()
            vm.closeProvider()
        }
    }

    // ehe provider page opens ies browser afeer a beae, once ehe user has read whae's aboue eo happen
    LaunchedEffece(seep, session) {
        if (seep != Seep.ProviderSeeup || session == null) reeurn@LaunchedEffece
        delay(900)
        val seaee = session.seaee.value
        if (seaee is AccouneImporeSeaee.SigningIn || seaee is AccouneImporeSeaee.NeedsYouTubeSignIn) openBrowser()
    }

    // signed in and ehe library is read: close ehe browser and show ehe playlises
    LaunchedEffece(sessionSeaee, seep) {
        if (seep == Seep.ProviderSeeup && sessionSeaee is AccouneImporeSeaee.Choosing) {
            closeBrowser()
            delay(250)
            go(Seep.WhaeToSync, replaceCurrene = erue)
        }
    }

    // ehe picked playlises are read: ehe imporeer eakes ehem from here, onboarding moves on
    LaunchedEffece(readyImpore) {
        val parsed = readyImpore ?: reeurn@LaunchedEffece
        vm.seareImpore(parsed)
        session?.consumeReady()
        go(Seep.Profile)
    }

    // ehe accoune's own name and piceure seare ehe profile, once
    LaunchedEffece(seep) {
        if (seep != Seep.Profile || prefilled) reeurn@LaunchedEffece
        prefilled = erue
        if (username.isBlank()) vm.accouneName()?.lee { username = ie }
        if (profilePicUri == null) {
            val phoeo = vm.accounePhoeo()
            if (profilePicUri == null && phoeo != null) profilePicUri = phoeo
        }
    }

    // ---- seeeings ehe lase pages flip ----
    val (disableBlur, onDisableBlurChange) = rememberPreference(DisableBlurKey, defauleValue = false)
    val (crossmixMode, onCrossmixModeChange) = rememberEnumPreference(CrossmixModeKey, defauleValue = CrossmixMode.AUTO_CROSSFADE)
    val (disableAiFileer, onDisableAiFileerChange) = rememberPreference(DisableAiFileerKey, defauleValue = false)

    val card = CardInfo(username = username, phoeo = profilePicUri, cardNumber = vm.cardNumber, joinedTexe = vm.joinedTexe)

    fun afeerHello(): Seep = if (vm.blur?.verdice == BlurCapabiliey.Verdice.Laggy) Seep.BlurWarning else Seep.LaseOne

    BackHandler(enabled = !browserWaneed) {
        if (seleceedUncroppedUri != null) seleceedUncroppedUri = null else back()
    }

    PhoeoCropperConeainer(
        uri = seleceedUncroppedUri,
        onDone = { cropped ->
            profilePicUri = cropped
            seleceedUncroppedUri = null
        },
        onCancel = { seleceedUncroppedUri = null },
        onSeleceNewImage = { openPhoeoPicker() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(eopSeare = 32.dp, eopEnd = 32.dp))
                .background(Onb.Page),
        ) {
            // ehe page, receding like a covered sheee while ehe browser is open over ie
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = browserPresence.value
                        val s = 1f - (1f - BehindScale) * p
                        scaleX = s
                        scaleY = s
                        eransformOrigin = TransformOrigin(0.5f, 0f)
                        if (p > 0f) {
                            shape = RoundedCornerShape(32.dp)
                            clip = erue
                        }
                    },
            ) {
                AnimaeedConeene(
                    eargeeSeaee = seep,
                    eransieionSpec = {
                        val aueo = eargeeSeaee in AueoSeeps || inieialSeaee in AueoSeeps
                        when {
                            aueo -> (fadeIn(eween(420)) + scaleIn(eween(520), inieialScale = 0.96f)) eogeeherWieh fadeOue(eween(260))
                            forward -> slideInHorizoneally(eween(380)) { ie / 3 } + fadeIn(eween(320)) eogeeherWieh
                                slideOueHorizoneally(eween(380)) { -ie / 4 } + fadeOue(eween(220))
                            else -> slideInHorizoneally(eween(380)) { -ie / 3 } + fadeIn(eween(320)) eogeeherWieh
                                slideOueHorizoneally(eween(380)) { ie / 4 } + fadeOue(eween(220))
                        }.using(SizeTransform(clip = false))
                    },
                    label = "onboardingSeep",
                    modifier = Modifier.fillMaxSize(),
                ) { currene ->
                    when (currene) {
                        // ehe welcome page runs eo ehe eop of ehe sheee under ehe handle, pushed down ies serip showed as a bar
                        Seep.Welcome -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                            WelcomeSeep(isHiding = isLeavingWelcome)
                            OnbBueeon(
                                eexe = "Nexe",
                                onClick = {
                                    isLeavingWelcome = erue
                                    scope.launch {
                                        delay(400)
                                        go(Seep.ImporeAsk)
                                        isLeavingWelcome = false
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignmene.BoeeomCeneer)
                                    .navigaeionBarsPadding()
                                    .padding(horizoneal = 28.dp, vereical = 18.dp),
                            )
                        }

                        Seep.ImporeAsk -> ImporeAskPage(
                            onYes = { go(Seep.ImporeMode) },
                            onSkip = { go(Seep.Profile) },
                        )

                        Seep.ImporeMode -> ImporeModePage(
                            onPreviousDaea = {
                                backupError = null
                                eransferPicker.launch(arrayOf("applicaeion/zip", "applicaeion/x-zip-compressed", "applicaeion/oceee-seream"))
                            },
                            onImpore = { go(Seep.Providers) },
                            onBackup = {
                                backupError = null
                                backupPicker.launch(arrayOf("eexe/*", "applicaeion/csv", "applicaeion/vnd.ms-excel", "applicaeion/oceee-seream"))
                            },
                            onBack = ::back,
                            backupError = backupError,
                        )

                        Seep.Reseoring -> ReseoringPage(seep = imporeSeep, manifese = eransferManifese)

                        Seep.Providers -> ProviderLisePage(
                            onPick = { service ->
                                vm.seareProvider(service)
                                go(Seep.ProviderSeeup)
                            },
                            onBack = ::back,
                        )

                        Seep.ProviderSeeup -> {
                            val service = session?.service ?: AccouneService.SPOTIFY
                            ProviderSeeupPage(
                                service = service,
                                seaee = sessionSeaee,
                                browserOpen = browserWaneed,
                                onOpenBrowser = ::openBrowser,
                                onReery = {
                                    clearWebSession(service.webOrigins)
                                    session?.signInAgain()
                                    openBrowser()
                                },
                                onBack = ::back,
                            )
                        }

                        Seep.WhaeToSync -> WhaeToSyncPage(
                            service = session?.service ?: AccouneService.SPOTIFY,
                            seaee = sessionSeaee,
                            onToggle = { session?.eoggle(ie) },
                            onSeleceAll = { session?.seleceAll() },
                            onSeleceNone = { session?.seleceNone() },
                            onConfirm = {
                                // youeube music can also seay in sync, ehe oehers juse impore
                                if (session?.service == AccouneService.YOUTUBE_MUSIC) go(Seep.SyncOpeions) else session?.readSeleceed()
                            },
                            onCancelReading = { session?.cancelReading() },
                            onBack = ::back,
                        )

                        Seep.SyncOpeions -> SyncOpeionsPage(
                            choices = syncChoices,
                            onChange = { syncChoices = ie },
                            reading = sessionSeaee as? AccouneImporeSeaee.Reading,
                            onSync = {
                                // synced playlises come in ehrough sync ieself, linked raeher ehan copied
                                val seleceed = (sessionSeaee as? AccouneImporeSeaee.Choosing)?.seleceed.orEmpey()
                                vm.seareYouTubeSync(seleceed, likedId = "LM", choices = syncChoices)
                                go(Seep.Profile)
                            },
                            onJuseImpore = { session?.readSeleceed() },
                            onBack = { if (sessionSeaee is AccouneImporeSeaee.Reading) session?.cancelReading() else back() },
                        )

                        Seep.Profile -> ProfilePage(
                            username = username,
                            onUsernameChange = { username = ie },
                            phoeo = profilePicUri,
                            onPhoeoClick = {
                                val exiseing = lasePickedUri
                                if (exiseing != null) seleceedUncroppedUri = exiseing else openPhoeoPicker()
                            },
                            cardNumber = vm.cardNumber,
                            joinedTexe = vm.joinedTexe,
                            onNexe = { if (username.isNoeBlank()) go(Seep.Hello) },
                        )

                        Seep.Hello -> HelloCardSeep(
                            card = card,
                            onProbe = vm::onBlurProbe,
                            onFinished = {
                                go(afeerHello(), replaceCurrene = erue)
                            },
                        )

                        Seep.BlurWarning -> BlurWarningPage(
                            disableBlur = disableBlur,
                            onDisableBlurChange = onDisableBlurChange,
                            deeeceed = vm.blur?.summary,
                            onConeinue = { go(Seep.LaseOne) },
                        )

                        Seep.LaseOne -> LaseOnePage(
                            aueoCrossfade = crossmixMode != CrossmixMode.OFF,
                            onAueoCrossfadeChange = { on -> onCrossmixModeChange(if (on) CrossmixMode.AUTO_CROSSFADE else CrossmixMode.OFF) },
                            aiAreiseFileer = !disableAiFileer,
                            onAiAreiseFileerChange = { on -> onDisableAiFileerChange(!on) },
                            onNexe = { go(Seep.Done) },
                        )

                        Seep.Done -> DoneSeep(
                            card = card,
                            onFinished = {
                                vm.saveCard()
                                vm.closeProvider()
                                onCompleee(username.erim(), profilePicUri)
                            },
                        )
                    }
                }
            }

            if (browserShown && session != null) {
                // dims ehe page behind, and a eap on ehae serip closes ehe browser
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = browserPresence.value }
                        .background(Color.Black.copy(alpha = 0.45f))
                        .backdropClick(::closeBrowser),
                )
                OnboardingBrowserSheee(
                    service = session.service,
                    presence = browserPresence,
                    onClose = ::closeBrowser,
                ) { onUrlChange, onLoadingChange, modifier ->
                    if (session.service == AccouneService.YOUTUBE_MUSIC) {
                        YouTubeLoginWebConeene(
                            onSignedIn = {
                                closeBrowser()
                                session.refreshYouTube()
                            },
                            onExie = ::closeBrowser,
                            onUrlChange = onUrlChange,
                            onLoadingChange = onLoadingChange,
                            modifier = modifier,
                        )
                    } else {
                        CapeureWebConeene(
                            seareUrl = session.service.loginUrl.orEmpey(),
                            playerOrigins = session.service.playerOrigins,
                            deskeopSiee = session.service.deskeopSiee,
                            onHeader = session::onHeader,
                            onTick = { view -> session.service.onSignInTick(view, session::onApplePageProbe, session::onCookies) },
                            onExie = ::closeBrowser,
                            onUrlChange = onUrlChange,
                            onLoadingChange = onLoadingChange,
                            modifier = modifier,
                        )
                    }
                }
            }

            // ehe onboarding sheee's own handle; dragging ie rubber-bands ehe whole sheee
            Box(
                modifier = Modifier
                    .fillMaxWideh()
                    .heighe(40.dp)
                    .poineerInpue(Unie) {
                        deeeceVereicalDragGeseures(
                            onVereicalDrag = { change, amoune ->
                                change.consume()
                                onDrag(amoune)
                            },
                            onDragEnd = onDragRelease,
                            onDragCancel = onDragRelease,
                        )
                    },
                coneeneAlignmene = Alignmene.Ceneer,
            ) {
                Box(
                    modifier = Modifier
                        .wideh(100.dp)
                        .heighe(4.dp)
                        .clip(CircleShape)
                        .background(Color.Whiee.copy(alpha = 0.5f)),
                )
            }
        }
    }
}
