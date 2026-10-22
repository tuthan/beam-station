package io.freemytv;

/** German copy for text drawn directly by the custom UI. Source keys are the existing English copy. */
final class German {
    private German() {}

    static String text(String english) {
        switch (english) {
            case "Live TV": return "Live-TV";
            case "All Apps": return "Alle Apps";
            case "Settings": return "Einstellungen";
            case "Home Settings": return "Start-Einstellungen";
            case "TV Settings": return "TV-Einstellungen";
            case "Pair a new device": return "Neues Gerät koppeln";
            case "Pairing…": return "Wird gekoppelt…";
            case "Pair": return "Koppeln";
            case "Remote": return "Fernbedienung";
            case "Disconnect": return "Trennen";
            case "The sound goes back to the projector.": return "Der Ton kommt wieder aus dem Projektor.";
            case "Play the projector's sound on it.": return "Der Ton des Projektors kommt aus diesem Gerät.";
            case "The Station disconnects from it now.": return "Die Station trennt sich jetzt davon.";
            case "The sound goes back to the glasses.": return "Der Ton geht zurück an die Brille.";
            case "Play the Station's sound on it.": return "Den Ton der Station darüber abspielen.";
            case "Forget this device": return "Dieses Gerät vergessen";
            case "Pair it again to use it.": return "Zum Benutzen erneut koppeln.";
            case "Paired with ": return "Gekoppelt mit ";
            case "Pairing with ": return "Kopple mit ";
            case "Couldn't pair with ": return "Koppeln fehlgeschlagen: ";
            case ". Put it in pairing mode and try again.": return ". Versetze es in den Kopplungsmodus und versuche es erneut.";
            case "Couldn't search for devices right now.": return "Gerade ist keine Gerätesuche möglich.";
            case "The remote stays as it is: pair or forget it in TV Settings.": return "Die Fernbedienung bleibt, wie sie ist: Koppeln oder Vergessen in den TV-Einstellungen.";
            case "Couldn't forget ": return "Konnte nicht vergessen: ";
            case ". Turn it on and try again.": return ". Schalte es ein und versuche es erneut.";
            case "Put the device in pairing mode, then pick it below.": return "Versetze das Gerät in den Kopplungsmodus und wähle es unten aus.";
            case "OK pairs, connects or forgets. The remote stays as it is.": return "OK koppelt, verbindet oder vergisst. Die Fernbedienung bleibt, wie sie ist.";
            case "Wi-Fi": return "WLAN";
            case "Not connected": return "Nicht verbunden";
            case "Connected": return "Verbunden";
            case "Saved": return "Gespeichert";
            case "Open": return "Offen";
            case "Forget this network": return "Dieses Netzwerk vergessen";
            case "The projector disconnects from it now.": return "Der Projektor trennt sich jetzt davon.";
            case "It won't join by itself anymore.": return "Es verbindet sich nicht mehr von selbst.";
            case "Keep it saved": return "Gespeichert lassen";
            case "Couldn't read the Wi-Fi: ": return "WLAN nicht lesbar: ";
            case "This network needs a sign-in " + Config.APP_NAME + " can't do: use TV Settings.": return "Dieses Netzwerk braucht eine Anmeldung, die " + Config.APP_NAME + " nicht kann: nutze die TV-Einstellungen.";
            case "Wi-Fi password": return "WLAN-Passwort";
            case "Connect": return "Verbinden";
            case "The password has 8 to 63 letters, numbers or symbols.": return "Das Passwort hat 8 bis 63 Buchstaben, Ziffern oder Zeichen.";
            case "Connecting to ": return "Verbinde mit ";
            case "Connected to ": return "Verbunden mit ";
            case "Couldn't connect to ": return "Keine Verbindung mit ";
            case ". Check the password.": return ". Prüfe das Passwort.";
            case "Forgot ": return "Vergessen: ";
            case "Searching for networks…": return "Suche nach Netzwerken…";
            case "Wi-Fi is off.": return "WLAN ist aus.";
            case "OK connects. Saved networks join by themselves.": return "OK verbindet. Gespeicherte Netzwerke verbinden sich von selbst.";
            case "Auto Focus": return "Autofokus";
            case "Auto Keystone": return "Trapezkorrektur";
            case "Speaker Mode": return "Lautsprechermodus";
            case "Speaker mode is on.": return "Lautsprechermodus ist an.";
            case "Speaker mode is off.": return "Lautsprechermodus ist aus.";
            case "Speaker mode isn't available right now.": return "Der Lautsprechermodus ist gerade nicht verfügbar.";
            case "Eco Mode": return "Eco-Modus";
            case "Eco mode isn't available right now.": return "Der Eco-Modus ist gerade nicht verfügbar.";
            case "Eco mode is on: less brightness and sound, more battery.": return "Eco-Modus an: weniger Helligkeit und Ton, mehr Akku.";
            case "Eco mode is off.": return "Eco-Modus aus.";
            case "Auto keystone when moved": return "Trapezkorrektur nach Bewegen";
            case "Auto focus when moved": return "Fokus nach Bewegen";
            case "Squaring the picture…": return "Bild wird ausgerichtet…";
            case "Time Zone": return "Zeitzone";
            case "Couldn't change it": return "Ließ sich nicht ändern";
            case "your city": return "deine Stadt";
            case "Game mode (HDMI)": return "Spielmodus (HDMI)";
            case "Game mode turns on by itself when the projector recognizes a console.": return "Der Spielmodus geht von selbst an, wenn der Projektor eine Konsole erkennt.";
            case "Picture": return "Bild";
            case "Picture mode": return "Bildmodus";
            case "Motion smoothing": return "Bewegungsglättung";
            case "AI": return "KI";
            case "Movie": return "Film";
            case "Sport": return "Sport";
            case "Custom": return "Benutzerdefiniert";
            case "Office": return "Büro";
            case "Performance": return "Leistung";
            case "Low": return "Niedrig";
            case "Medium": return "Mittel";
            case "High": return "Hoch";
            case "Changes show at once.": return "Änderungen sind sofort zu sehen.";
            case "Couldn't read the projector's picture settings.": return "Die Bildeinstellungen des Projektors ließen sich nicht lesen.";
            case "The projector didn't take that change.": return "Der Projektor hat diese Änderung nicht übernommen.";
            case "Glasses": return "Brille";
            case "Mode": return "Modus";
            case "Refresh rate": return "Bildwiederholrate";
            case "Speakers": return "Lautsprecher";
            case "Standard": return "Standard";
            case "Reading": return "Lesen";
            case "Eye care": return "Augenschonend";
            case "3D (side by side)": return "3D (nebeneinander)";
            case "Private": return "Privat";
            case "Public": return "Öffentlich";
            case "120 Hz is for 2D only.": return "120 Hz gibt es nur in 2D.";
            case "Couldn't reach the glasses.": return "Die Brille war nicht erreichbar.";
            case "The glasses didn't take that change.": return "Die Brille hat diese Änderung nicht übernommen.";
            case "Home puts the glasses back in 2D.": return "Der Startbildschirm schaltet die Brille zurück auf 2D.";
            case "Switch to 3D": return "Zu 3D wechseln";
            case "Switch to 2D": return "Zu 2D wechseln";
            case "The glasses are in 3D.": return "Die Brille ist in 3D.";
            case "The glasses are in 2D.": return "Die Brille ist in 2D.";
            case "Brightness": return "Helligkeit";
            case "Display zoom": return "Bildschirmzoom";
            case "Couldn't open it.": return "Ließ sich nicht öffnen.";
            case "Power": return "Energie";
            case "Battery": return "Akku";
            case "Screensaver after": return "Bildschirmschoner nach";
            case "Sleep after": return "Ruhezustand nach";
            case "Sleep timer": return "Sleep-Timer";
            case "Sleep Timer": return "Sleep-Timer";
            case "Charging": return "Lädt";
            case "On battery": return "Akkubetrieb";
            case "Never": return "Nie";
            case "Couldn't read the power settings.": return "Die Energieeinstellungen ließen sich nicht lesen.";
            case "Couldn't change the power settings.": return "Die Energieeinstellungen ließen sich nicht ändern.";
            case "The Station didn't take that change.": return "Die Station hat diese Änderung nicht übernommen.";
            case "While plugged in, the screensaver doesn't start.": return "Am Netz startet der Bildschirmschoner nicht.";
            case "Sleep timer off.": return "Sleep-Timer aus.";
            case "Sleep timer: ": return "Sleep-Timer: ";
            case "HDMI Inputs": return "HDMI-Eingänge";
            case "Inputs": return "Eingänge";
            case "Shortcuts": return "Verknüpfungen";
            case "Weather": return "Wetter";
            case "No weather data right now.": return "Gerade keine Wetterdaten.";
            case "Sunrise ": return "Sonnenaufgang ";
            case "Sunset ": return "Sonnenuntergang ";
            case "Crypto": return "Krypto";
            case "24h change": return "Änderung in 24 h";
            case "For You": return "Für dich";
            case "Couldn't open ": return "Konnte nicht öffnen: ";
            case " of ": return " von ";
            case "← → to move · OK to drop · Back to cancel": return "← → verschieben · OK ablegen · Zurück abbrechen";
            case "Moving ": return "Verschiebe ";
            case ", position ": return ", Position ";
            case ", tab ": return ", Tab ";
            case " of 3": return " von 3";
            case "Dollar": return "Dollar";
            case "Series": return "Serie";
            case "prices from ": return "Kurse von ";
            case "Starting…": return "Wird gestartet…";
            case "Original": return "Original";
            case "Home Screen, ": return "Startbildschirm, ";
            case "Screensaver, preview": return "Bildschirmschoner, Vorschau";
            case "no ads, with the new screensaver": return "ohne Werbung, mit dem neuen Bildschirmschoner";
            case "the TV's factory home screen": return "der Startbildschirm ab Werk";
            case ". In use": return ". Aktiv";
            case " of 2": return " von 2";
            case "Your TV's home screen and screensaver": return "Startbildschirm und Bildschirmschoner des Fernsehers";
            case "Home Screen": return "Startbildschirm";
            case "Screensaver": return "Bildschirmschoner";
            case "Preview": return "Ansehen";
            case "On the TV: tick “Always allow” and press OK.": return "Am Fernseher: „Immer zulassen“ ankreuzen und OK drücken.";
            case "RECOMMENDATIONS": return "EMPFEHLUNGEN";
            case "No TMDB key: see the README (config.properties).": return "Kein TMDB-Schlüssel: siehe README (config.properties).";
            case "Error: ": return "Fehler: ";
            case "Fetching titles…": return "Titel werden geladen…";
            case "Links to the titles by Watchmode.": return "Links zu den Titeln von Watchmode.";
            case "This product uses the TMDB API but is not endorsed or certified by TMDB.": return "Nutzt die TMDB-API, ohne Billigung oder Zertifizierung durch TMDB.";
            case "COMPARISON": return "VERGLEICH";
            case "Home button": return "Home-Taste";
            case "Free memory": return "Freier Speicher";
            case "Home screen memory": return "Speicher des Startbildschirms";
            case "NOTICES": return "HINWEISE";
            case "None yet.": return "Noch keine.";
            case "No ads, with the new screensaver": return "Ohne Werbung, mit dem neuen Bildschirmschoner";
            case "The TV's factory home screen": return "Der Startbildschirm ab Werk";
            case "Switching takes a few seconds; the screen blinks while it measures.": return "Der Wechsel dauert ein paar Sekunden; beim Messen flackert der Bildschirm.";
            case "This projector lets any computer on the network connect without asking.": return "Dieser Projektor lässt jeden Computer im Netzwerk ohne Rückfrage zu.";
            case "now": return "jetzt";
            case "Measuring the original home screen…": return "Der ursprüngliche Startbildschirm wird gemessen…";
            case "Switching the home screen and screensaver…": return "Startbildschirm und Bildschirmschoner werden gewechselt…";
            case "Android didn't accept the new home screen": return "Android hat den neuen Startbildschirm nicht angenommen";
            case "Turning off apps (": return "Apps werden ausgeschaltet (";
            case "Measuring " + Config.APP_NAME + "…": return Config.APP_NAME + " wird gemessen…";
            case "Couldn't turn on " + Config.APP_NAME + ": ": return Config.APP_NAME + " ließ sich nicht einschalten: ";
            case "Turning the apps back on…": return "Die Apps werden wieder eingeschaltet…";
            case "Turning apps back on (": return "Apps werden wieder eingeschaltet (";
            case "Restoring the original home screen…": return "Der ursprüngliche Startbildschirm wird wiederhergestellt…";
            case "Original mode: the factory home screen, screensaver and apps are back.": return "Originalmodus: Startbildschirm, Bildschirmschoner und Apps ab Werk sind zurück.";
            case "the original home screen": return "den ursprünglichen Startbildschirm";
            case "Original mode, but I couldn't turn back on: ": return "Originalmodus, aber das ließ sich nicht wieder einschalten: ";
            case "Couldn't go back to the original: ": return "Zurück zum Original ging nicht: ";
            case Config.APP_NAME + "'s home screen crashed 3 times in a row; I switched back to the original one.": return "Der Startbildschirm von " + Config.APP_NAME + " ist 3-mal hintereinander abgestürzt; der ursprüngliche ist wieder aktiv.";
            case "the home screen": return "den Startbildschirm";
            case "the screensaver": return "den Bildschirmschoner";
            case "Couldn't check " + Config.APP_NAME + ": ": return Config.APP_NAME + " ließ sich nicht prüfen: ";
            case "The TV update turned back on ": return "Das TV-Update hat wieder eingeschaltet: ";
            case "Turned off what was missing: ": return "Ausgeschaltet, was noch fehlte: ";
            case "The TV was updated; " + Config.APP_NAME + " is still on.": return "Der Fernseher wurde aktualisiert; " + Config.APP_NAME + " ist weiter aktiv.";
            case "Connecting…": return "Verbindung wird hergestellt…";
            case "Accept “Allow USB debugging?” on the TV (tick “Always allow”).": return "Am Fernseher „USB-Debugging zulassen?“ bestätigen („Immer zulassen“ ankreuzen).";
            case Config.APP_NAME + "'s home screen ran into a problem.\nGoing back to the TV's original home screen…": return "Der Startbildschirm von " + Config.APP_NAME + " hatte ein Problem.\nZurück zum ursprünglichen Startbildschirm…";
            case "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                    + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                    + "show there: Claude Code can remove them from your computer), then try once more.":
                return "die Ersteinrichtung des Fernsehers hat das WLAN gespeichert, und sie auszuschalten würde diese Netzwerke löschen. "
                        + "Unter Einstellungen → Netzwerk und Internet das Netzwerk entfernen und neu verbinden (Netzwerke außer Reichweite "
                        + "erscheinen dort nicht: Claude Code entfernt sie vom Computer aus), dann noch einmal versuchen.";
            // Home Settings, Shelf and Remote Buttons
            case "Language": return "Sprache";
            case "Temperature": return "Temperatur";
            case "Theme": return "Design";
            case "Automatic": return "Automatisch";
            case "Light": return "Hell";
            case "Dark": return "Dunkel";
            case "Navigation sounds": return "Navigationstöne";
            case "Weather widget": return "Wetter-Widget";
            // What TalkBack reads on the drawer's weather card (#47)
            case ". High ": return ". Höchstwert ";
            case ", low ": return ", Tiefstwert ";
            case " Next hours: ": return " Nächste Stunden: ";
            case " Sunrise ": return " Sonnenaufgang ";
            case " Sunset ": return " Sonnenuntergang ";
            case "City": return "Stadt";
            case "Searching…": return "Suche…";
            case "Type the city's name": return "Namen der Stadt eingeben";
            case "Search": return "Suchen";
            case "Cancel": return "Abbrechen";
            case "No city with that name.": return "Keine Stadt mit diesem Namen.";
            case "Couldn't search now: ": return "Suche gerade nicht möglich: ";
            case "Quotes widget": return "Kurs-Widget";
            case "On": return "An";
            case "Off": return "Aus";
            case "Shelf": return "Leiste";
            case "on the shelf, place ": return "in der Leiste, Platz ";
            case "not on the shelf": return "nicht in der Leiste";
            case "Remote Buttons": return "Fernbedienungstasten";
            case "Netflix button": return "Netflix-Taste";
            case "YouTube button": return "YouTube-Taste";
            case "Prime Video button": return "Prime-Video-Taste";
            case "TCL Channel button": return "TCL-Channel-Taste";
            case "Tabii button": return "Tabii-Taste";
            case "Nothing": return "Nichts";
            case "BRAVIA CORE button": return "BRAVIA-CORE-Taste";
            case "Disney+ button": return "Disney+-Taste";
            case "Crunchyroll button": return "Crunchyroll-Taste";
            case "Sony default": return "Sony-Standard";
            case "Sony default: the button opens what it always did.": return "Sony-Standard: Die Taste öffnet, was sie immer geöffnet hat.";
            case "Not installed": return "Nicht installiert";
            case ", selected": return ", ausgewählt";
            case "OK or the arrows change the value.": return "OK oder die Pfeiltasten ändern den Wert.";
            case "OK picks, Back cancels.": return "OK wählt aus, Zurück bricht ab.";
            case "Disney and Globoplay: the TV itself controls those buttons.": return "Disney und Globoplay: Diese Tasten steuert der Fernseher selbst.";
            case "YouTube, Prime Video and Google Play: the TV itself controls those buttons.": return "YouTube, Prime Video und Google Play: Diese Tasten steuert der Fernseher selbst.";
            case "Netflix, YouTube and Prime Video: the TV itself controls those buttons.": return "Netflix, YouTube und Prime Video: Diese Tasten steuert der Fernseher selbst.";
            case "The other buttons: the TV itself controls them.": return "Die anderen Tasten steuert der Fernseher selbst.";
            case "TCL remote buttons: on this TV they work as always.": return "TCL-Fernbedienung: Die Tasten funktionieren hier wie immer.";
            default:
                if (english.startsWith(Config.APP_NAME + " is on: new home screen and screensaver, ")) {
                    return Config.APP_NAME + " ist an: neuer Startbildschirm und Bildschirmschoner, "
                            + english.substring((Config.APP_NAME + " is on: new home screen and screensaver, ").length()).replace(" apps turned off.", " Apps ausgeschaltet.");
                }
                if (english.startsWith("On · ")) {
                    return "An · " + english.substring(5).replace(" titles from the TV's apps, 3 new ones every hour.",
                            " Titel aus den Apps des Fernsehers, 3 neue pro Stunde.");
                }
                if (english.startsWith("OK adds or removes (up to ")) {
                    return "OK fügt hinzu oder entfernt (bis zu " + english.substring("OK adds or removes (up to ".length())
                            .replace("). ← → change the order.", "). ← → ändern die Reihenfolge.");
                }
                if (english.endsWith(" min ago")) return "vor " + english.substring(0, english.length() - 8) + " Min.";
                if (english.endsWith(" h ago")) return "vor " + english.substring(0, english.length() - 6) + " Std.";
                if (english.endsWith(" days ago")) return "vor " + english.substring(0, english.length() - 9) + " Tagen";
                return english;
        }
    }
}
