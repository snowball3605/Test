import wave     # for processing wav file
import os       # for removing a file
import array    # for converting between types of array
import re       # for regular expression
import zipfile  # for reading zip files

##### YOU DO NOT NEED TO UNDERSTAND THE CODE IN THIS FILE

##### Class of the music module

class Music:

    # Available instrument list
    instrument_list = [
        "acoustic grand piano", "bright acoustic piano", "electric grand piano",
        "honky-tonk piano", "electric piano 1", "electric piano 2", "harpsichord",
        "clavinet", "celesta", "glockenspiel", "music box", "vibraphone",
        "marimba", "xylophone", "tubular bells", "dulcimer", "drawbar organ",
        "percussive organ", "rock organ", "church organ", "reed organ",
        "accordion", "harmonica", "tango accordion", "acoustic guitar (nylon)",
        "acoustic guitar (steel)", "electric guitar (jazz)",
        "electric guitar (clean)", "electric guitar (muted)", "overdriven guitar",
        "distortion guitar", "guitar harmonics", "acoustic bass",
        "electric bass (finger)", "electric bass (pick)", "fretless bass",
        "slap bass 1", "slap bass 2", "synth bass 1", "synth bass 2", "violin",
        "viola", "cello", "contrabass", "tremolo strings", "pizzicato strings",
        "orchestral harp", "timpani", "string ensemble 1", "string ensemble 2",
        "synth strings 1", "synth strings 2", "choir aahs", "voice oohs",
        "synth choir", "orchestra hit", "trumpet", "trombone", "tuba",
        "muted trumpet", "french horn", "brass section", "synth brass 1",
        "synth brass 2", "soprano sax", "alto sax", "tenor sax", "baritone sax",
        "oboe", "english horn", "bassoon", "clarinet", "piccolo", "flute",
        "recorder", "pan flute", "blown bottle", "shakuhachi", "whistle",
        "ocarina", "lead 1 (square)", "lead 2 (sawtooth)", "lead 3 (calliope)",
        "lead 4 (chiff)", "lead 5 (charang)", "lead 6 (voice)", "lead 7 (fifths)",
        "lead 8 (bass + lead)", "pad 1 (new age)", "pad 2 (warm)",
        "pad 3 (polysynth)", "pad 4 (choir)", "pad 5 (bowed)", "pad 6 (metallic)",
        "pad 7 (halo)", "pad 8 (sweep)", "fx 1 (rain)", "fx 2 (soundtrack)",
        "fx 3 (crystal)", "fx 4 (atmosphere)", "fx 5 (brightness)",
        "fx 6 (goblins)", "fx 7 (echoes)", "fx 8 (sci-fi)", "sitar", "banjo",
        "shamisen", "koto", "kalimba", "bagpipe", "fiddle", "shanai",
        "tinkle bell", "agogo", "steel drums", "woodblock", "taiko drum",
        "melodic tom", "synth drum", "reverse cymbal", "guitar fret noise",
        "breath noise", "seashore", "bird tweet", "telephone ring", "helicopter",
        "applause", "gunshot"]

    def __init__(self):
        # Default wave file params -
        # (mono, 2-bytes per sample, 44100 sample rate, length, no compression)
        self.wave_params = (1, 2, 44100, 0, 'NONE', 'not compressed')

        # Audio parameters
        self.sample_rate = self.wave_params[2]
        self.quantization = self.wave_params[1] * 8
        self.fadeout_duration = 0.1

        # Default instrument - acoustic grand piano (0)
        self.current_instrument = 0

        # Sample data
        self.music_samples = []

        # The playing process (for Mac)
        self.playing_process = None


    # Get the song list in the song folder
    def get_song_list(self):
        song_list = []
        base = os.path.dirname(os.path.abspath(__file__))
        for name in os.listdir(os.path.join(base, "songs")):
            if name.endswith(".txt"):
                song_list.append((name, os.path.join(base, "songs", name)))
        return song_list


    # Clear the current music data
    def clear(self):
        self.music_samples = []


    # Get the currently available instruments
    def get_available_instruments(self):
        instruments = []

        for instrument in range(len(self.instrument_list)):
            # Get the instrument zip name
            instrument_file = self.instrument_list[instrument]
            instrument_file = re.sub("[^a-zA-Z0-9]+", "_", instrument_file)
            instrument_file = re.sub("_$", "", instrument_file) + ".zip"

            # Check whether the instrument is available
            base = os.path.dirname(os.path.abspath(__file__))
            path = os.path.join(base, "soundfont", instrument_file)
            if os.path.isfile(path):
                instruments.append(instrument)

        return instruments


    # Change the instrument to a new instrument
    def set_instrument(self, instrument):
        if isinstance(instrument, int) and 0 <= instrument < 128:
            self.current_instrument = instrument


    # Get the current instrument name
    def get_instrument_name(self, instrument=None):
        if instrument is None:
            instrument = self.current_instrument
        return self.instrument_list[instrument]


    # Apply fade out to the sound
    def __fadeout(self, sound_data, time):
        # Find the number of fade out samples
        total_samples = len(sound_data)
        fadeout_samples = int(time * self.sample_rate)
        if fadeout_samples > total_samples:
            fadeout_samples = total_samples
        
        # Apply fade out to the sound file
        for i in range(fadeout_samples):
            multiplier = 1 - (i + 1) / fadeout_samples
            index = total_samples - fadeout_samples + i
            sound_data[index] = int(sound_data[index] * multiplier)


    # Normalize the sound
    def __normalize(self, sound_data):
        # Find the maximum sample value in the data
        min_sample = min(sound_data)
        if min_sample < 0: min_sample = -min_sample
        max_sample = max(min_sample, max(sound_data))
        
        if max_sample > 0:
            # Adjust the sample data
            multiplier = (int(2 ** self.quantization / 2) - 1) / max_sample
            for i in range(len(sound_data)):
                sound_data[i] = int(sound_data[i] * multiplier)
    

    # Add a new note at the appropriate location
    def add_note(self, time, pitch, duration):
        # Find the note name and file
        note_key = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]
        octave = (pitch - 12) // 12 
        note_name = note_key[pitch % 12] + str(octave)
        note_file = note_name + ".wav"

        # Find the instrument and get the zip name
        instrument_file = self.instrument_list[self.current_instrument]
        instrument_file = re.sub("[^a-zA-Z0-9]+", "_", instrument_file)
        instrument_file = re.sub("_$", "", instrument_file) + ".zip"

        # Open the instrument zip file    
        base = os.path.dirname(os.path.abspath(__file__))
        zip_file = os.path.join(base, "soundfont", instrument_file)
        with zipfile.ZipFile(zip_file, "r") as instrument_zip:
            # Check if the note exists
            if note_file not in instrument_zip.namelist():
                return
    
            # Read the sound samples
            sound_samples = []
            with instrument_zip.open(note_file, "r") as file:
                with wave.open(file, "rb") as wave_file:
                    sound_samples = wave_file.readframes(wave_file.getnframes())
            sound_data = array.array("h", sound_samples)

        # Find the length of data needed and trim the sound if necessary
        total_samples = int((duration + self.fadeout_duration) * self.sample_rate)
        if len(sound_data) > total_samples:
            sound_data = sound_data[:total_samples]
        else:
            total_samples = len(sound_data)

        # Find the location in samples
        start_pos = int(time * self.sample_rate)
        end_pos = start_pos + total_samples - 1

        # Padding zeros for adding the note in the samples list, if needed
        if len(self.music_samples) < end_pos + 1:
            zeros = [0] * (end_pos - len(self.music_samples) + 1)
            self.music_samples.extend(zeros)

        # Apply fade out to the sound samples
        __fadeout(sound_data, self.fadeout_duration)

        # Add the sound samples to the music samples
        for i in range(len(sound_data)):
            self.music_samples[start_pos + i] += sound_data[i]


    # Play the current music data
    def play(self):
        # Stop any currently playing sound
        self.stop()

        # Create a temporary wav file
        tempname = "temp_music.wav"

        # Read the wave file
        with wave.open(tempname, "wb") as wave_file:
            # Set the wav parameters
            wave_file.setparams(self.wave_params)

            # Normalize the data
            if len(self.music_samples) > 0:
                self.__normalize(self.music_samples)

            # Add some silence at the end
            sample_array = self.music_samples + \
                           [0] * int(self.sample_rate * 0.5)

            # Write the all samples
            sample_array = array.array('h', sample_array)
            wave_file.writeframes(sample_array.tobytes())

        # Now we play the temporary file
        from sys import platform

        # Here we check what platform you are using,
        # then we play the soundfile using the appropriate technique
        if platform.lower().startswith("win"): # Microsoft Windows is "win32" or maybe "windows"
            import winsound
            winsound.PlaySound(tempname, winsound.SND_FILENAME | winsound.SND_ASYNC)
        elif platform.lower().startswith("dar"): # MacOS is "darwin"
            import subprocess
            # All recent MacOs has the afplay program
            self.playing_process = subprocess.Popen(["afplay", tempname])
        else: # Linux is "linux" or maybe "linux2"
            print("You are not using a Windows or a Mac system!")
            print("Maybe you are using a Linux system - ?")
            print("Unfortunately, I don't know how to reliably play sounds")
            print("on your system (without any extra Python installations).")
            print("So sorry about that! DR")


    # Stop any currently playing sound
    def stop(self, delete_sound=False):
        from sys import platform

        # Here we check what platform you are using,
        # then we kill the previously playing soundfile
        if platform.lower().startswith('win'): # Microsoft Windows is "win32" or maybe "windows"
            import winsound
            winsound.PlaySound(None, winsound.SND_ASYNC)
        elif platform.lower().startswith('dar'): # MacOS is "darwin"
            if self.playing_process != None:
                self.playing_process.kill()
                self.playing_process = None

        # Remove the temporary wav file
        try:
            os.remove("temp_music.wav")
        except:
            pass


__module__ = Music()
for method_name in dir(__module__):
    if callable(getattr(__module__, method_name)) and not method_name.startswith("__"):
        globals()[method_name] = getattr(__module__, method_name)

