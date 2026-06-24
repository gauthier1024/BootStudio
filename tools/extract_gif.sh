#!/bin/sh

clear

printf "\033[1;36m"
echo "╔══════════════════════════════════════╗"
echo "║     Bootanimation → GIF Preview     ║"
echo "╚══════════════════════════════════════╝"
printf "\033[0m\n"


printf "\033[1;33mBootanimation ZIP:\033[0m "
read -r ZIP


if [ ! -f "$ZIP" ]; then
    echo "[!] ZIP not found"
    exit 1
fi


NAME=$(basename "$ZIP" .zip)
TMP=$(mktemp -d)


cleanup() {
    rm -rf "$TMP"
}
trap cleanup EXIT


mkdir -p "$TMP/extract"
mkdir -p "$TMP/frames"


echo
echo "Extracting ZIP..."

unzip -oq "$ZIP" -d "$TMP/extract"


DESC="$TMP/extract/desc.txt"


if [ ! -f "$DESC" ]; then
    echo "[!] desc.txt missing"
    exit 1
fi


FPS=$(head -n1 "$DESC" | awk '{print $3}')


echo
echo "Original FPS: $FPS"


INDEX=0


echo
echo "Extracting parts..."


# récupère uniquement les dossiers dans l'ordre du desc.txt
PARTS=$(awk '$1=="c" || $1=="p" {print $4}' "$DESC")


for PART in $PARTS
do
    DIR="$TMP/extract/$PART"

    if [ ! -d "$DIR" ]; then
        echo "[!] Missing $PART"
        continue
    fi


    echo " -> $PART"


    # tri des images
    FILES=$(find "$DIR" -maxdepth 1 -type f | sort)


    for IMG in $FILES
    do
        TYPE=$(file "$IMG")


        case "$TYPE" in
            *PNG*|*JPEG*)
                ;;
            *)
                continue
                ;;
        esac


        OUT=$(printf "%s/frames/%06d.jpg" "$TMP" "$INDEX")


        ffmpeg -y -loglevel error \
            -i "$IMG" \
            -q:v 12 \
            "$OUT"


        INDEX=$((INDEX + 1))
    done

done


if [ "$INDEX" -eq 0 ]; then
    echo "[!] No frames found"
    exit 1
fi


echo
echo "Frames extracted: $INDEX"
echo "Creating GIF..."


ffmpeg -y -loglevel error \
    -framerate "$FPS" \
    -i "$TMP/frames/%06d.jpg" \
    -vf "fps=15,crop=min(iw\,ih):min(iw\,ih):(iw-min(iw\,ih))/2:(ih-min(iw\,ih))/2,scale=256:256,setpts=PTS/4" \
    -loop 0 \
    "$NAME.gif"


if [ -f "$NAME.gif" ] && [ -s "$NAME.gif" ]; then

    SIZE=$(du -h "$NAME.gif" | cut -f1)

    echo
    printf "\033[1;32m[✓] GIF created\033[0m\n"
    echo "File : $NAME.gif"
    echo "Size : $SIZE"

else

    echo "[!] GIF creation failed"

fi
