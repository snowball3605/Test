# COMP1021 Music System

# You need to run this file, not music_processing.py, to start the music system


##### YOU DO NOT NEED TO UNDERSTAND THE CODE IN THIS FILE


import turtle # Import the turtle module for drawing
import music  # Import the music module for playing music
import music_processing # Import the music processing module for processing the music data


# Initialize the music data
music_data = []


# A dictionary containing the menu settings
main_menu = {
    # menu key: (caption, position and size, colour)
    "load": ("Load Music", (-240, 90, 200, 120), "cyan"),
    "play": ("Play Music", (0, 90, 200, 120), "yellow"),
    "clear": ("Clear Music", (240, 90, 200, 120), "pink"),
    "instrument": ("Change Instrument", (-240, -70, 200, 120), "magenta"),
    "transpose": ("Transpose Music", (0, -70, 200, 120), "orange"),
    "speed": ("Adjust Speed", (240, -70, 200, 120), "red"),
    "repeat": ("Repeat Music", (0, -230, 200, 120), "light green")
}


# This function draws a coloured box at (x, y) with a size of (w, h)
def draw_box(color, x, y, w, h):
    turtle.fillcolor(color)
    turtle.goto(x - w / 2, y - h / 2)
    turtle.down()
    turtle.begin_fill()
    for _ in range(2):
        turtle.forward(w)
        turtle.left(90)
        turtle.forward(h)
        turtle.left(90)
    turtle.end_fill()
    turtle.up()
    turtle.goto(x, y)

    
# This function creates the menu on the turtle window
def draw_menu():
    turtle.hideturtle()
    turtle.up()
    turtle.width(4)

    turtle.tracer(False)    # Disable any turtle animation
    
    turtle.clear()

    # Write the title
    turtle.goto(0, 250)
    turtle.write("Python Music System", align="center", \
                 font=("Arial", 30, "bold"))

    # Draw the menu boxes
    for menu_info in main_menu.values():
        caption = menu_info[0]
        x, y, w, h = menu_info[1]
        color = menu_info[2]

        draw_box(color, x, y, w, h)

        turtle.goto(x, y - 10)
        turtle.write(caption, align="center", \
                     font=("Arial", 14, "bold"))

    turtle.tracer(True)    # Refresh the turtle window


# This function shows the music summary
def update_summary():
    text_turtle.up()
    text_turtle.hideturtle()

    text_turtle.clear()
    text_turtle.goto(0, 200)

    if len(music_data) == 0:
        # Music is empty
        summary = "Click on the 'Load Music' area to load a music file"
    else:
        # Number of notes
        summary = "No. of notes = " + str(len(music_data)) + ", "

        # Duration
        duration = 0
        for note in music_data:
            if note[0] + note[2] > duration:
                duration = note[0] + note[2]
        mins = int(duration / 60)
        secs = round(duration % 60, 2)
        summary += "song duration = " + str(mins) + "m " + str(secs) + "s, "

        # Instrument
        summary += "instrument = " + music.get_instrument_name()
        
    text_turtle.write(summary, align="center", font=("Arial", 14, "normal"))
    turtle.listen()


# This function loads some music into the music data list
def load_music():
    global music_data

    # Get the song list from the song folder
    song_list = music.get_song_list()
    song_menu = ""
    for i in range(len(song_list)):
        song_menu = song_menu + str(i) + ": " + song_list[i][0] + "\n"
    if song_menu == "":
        song_menu = "No music files available"
    
    # Ask the user for the music file
    filename = turtle.textinput("Music File", song_menu + \
                   "\nPlease give me a music file number:")
    if filename == None:
        return      # If the user enters nothing or not a number then stop this function now
    if not filename.isnumeric() or not(0 <= int(filename) < len(song_list)):
        return

    # Get the song for numeric input
    filename = song_list[int(filename)][1]

    # Open the file for reading
    file = open(filename, "r")

    # Reset the music data
    music_data = []

    # Read the data into the music list
    for line in file:
        # Read each line as a music note
        note = line.rstrip().split("\t")

        # Convert the data to the right data type
        note[0] = float(note[0])  # Time
        note[1] = int(note[1])    # Pitch
        note[2] = float(note[2])  # Duration

        # Add the note at the end of the music
        music_data.append(note)

    # Close the file
    file.close()

    # Update the music summary
    update_summary()


# This function plays the music
def play_music():
    global music_data

    # Clear the music data in the music module
    music.clear()

    # Add the music notes
    for i in range(len(music_data)):
        # Show progress every 10 notes
        if i % 10 == 0:
            turtle.tracer(False)
            text_turtle.clear()
            text_turtle.write("Adding note " + str(i) + \
                              " of " + str(len(music_data)), \
                              align="center", font=("Arial", 14, "normal"))
            turtle.tracer(True)

        # Add the note
        note = music_data[i]
        music.add_note(note[0], note[1], note[2])

    # Update the music summary
    update_summary()

    # Play the music
    music.play()


# This function clear the cureent load music
def clear_music():
    global music_data
    
    # Clear the current music data
    music_data = []

    # Update the music summary
    update_summary()


# This function returns the available instrument list
def get_instrument_list():
    # Get the available instrument list
    instruments = music.get_available_instruments()

    # Build the instrument list
    instrument_list = ""
    for index in range(len(instruments)):
        instrument_list = instrument_list + str(instruments[index]) + " : " + \
                          music.get_instrument_name(instruments[index]) + "\n"
        if index > 9:
            instrument_list = instrument_list + "\n" + \
                              "...only the first 10 are shown...\n"
            break

    return instrument_list


# This function changes the instrument
def change_instrument():
    # Get the instrument list
    message = get_instrument_list() + "\n" + \
              "Please enter the instrument number (0-127):"

    # Ask the user for the instrument number
    instrument = turtle.numinput("Change Instrument", message)
    if instrument == None:
        return

    # Convert the variable to an appropiate type
    instrument = int(instrument)

    # Change the instrument appropriately
    music.set_instrument(instrument)

    # Update the music summary
    update_summary()


# This function transposes the music pitch
def transpose():
    global music_data

    # Ask the user for the transposition number
    pitch_change = turtle.numinput("Transpose", "Please enter the transposition:")
    if pitch_change == None:
        return

    # Convert the variable to an appropiate type
    pitch_change = int(pitch_change)

    # Adjust the pitch of all notes appropriately
    music_data = music_processing.transpose(music_data, pitch_change)

    # Update the music summary
    update_summary()


# This function adjusts the speed of the music
def adjust_speed():
    global music_data

    # Ask the user for the speed change
    speed_change = turtle.numinput("Adjust Speed", \
                  "Please enter the new speed, in percentage:")
    if speed_change == None:
        return

    # Convert the variable to an appropiate type
    speed_change = int(speed_change)

    # Adjust the speed of all notes appropriately
    music_data = music_processing.adjust_speed(music_data, speed_change)

    # Update the music summary
    update_summary()


# This function will repeat the music multiple times
def repeat_music():
    global music_data

    # Ask the user for number of times to repeat
    repeat_count = turtle.numinput("Repeat Music", \
                   "Please enter the number of times to repeat:")
    if repeat_count == None:
        return
    
    # Convert the variables to appropiate types
    repeat_count = int(repeat_count)

    # Repeat the music multiple times
    music_data = music_processing.repeat_music(music_data, repeat_count)

    # Update the music summary
    update_summary()


# This function handles the screen click and the menu selection
def handleMenu(x, y):
    # Get the menu item that the user has clicked on
    selected_key = None
    for key, menu_info in main_menu.items():
        menux, menuy, menuw, menuh = menu_info[1]
        if x > menux - menuw / 2 and x < menux + menuw / 2 and \
           y > menuy - menuh / 2 and y < menuy + menuh / 2:
            selected_key = key

    # Run the corresponding functions for each menu item
    if selected_key == "load":
        load_music()
    elif selected_key == "play":
        play_music()
    elif selected_key == "clear":
        clear_music()
    elif selected_key == "instrument":
        change_instrument()
    elif selected_key == "transpose":
        transpose()
    elif selected_key == "speed":
        adjust_speed()
    elif selected_key == "repeat":
        repeat_music()


# This function prints the current music data in the output
def print_music_data():
    global music_data

    # Print the music data
    music_processing.print_music_data(music_data)


# Set up the turtle module
turtle.setup(800, 700)
turtle.speed(0)

# Show the menu
draw_menu()

# Create a new turtle to show music summary
text_turtle = turtle.Turtle()

# Update the music summary
update_summary()

# Set up the screen click event
turtle.onscreenclick(handleMenu)

# Set up the print key event
turtle.onkeypress(print_music_data, "p")
turtle.listen()

turtle.done()

# Kill any currently playing sounds and remove the sound
music.stop(True)
