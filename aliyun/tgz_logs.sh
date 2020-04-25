files=10
days=7

log_dirs=(
/var/log/logserver
)

for ((i=0; i<${#log_dirs[@]}; ++i))
do
log_dir=${log_dirs[i]}
echo $log_dir
guard=`ls $log_dir -ltr | head -n -$files | tail -n 1 | awk '{print $9}'`;
echo "guard is $guard"

if [ -n "$guard" ]; then
log_files=`find $log_dir -mtime +$days ! -newer $log_dir/$guard | sort -r`

for log_file in $log_files
do
if [ ${log_file##*.} != "gz" ]; then
echo $log_file tgz
#tar zcvf $log_file.tgz -C $log_dir ${log_file##*/}
gzip -qf $log_file
#rm -f $log_file
fi
done

else
echo "files less than $files"
fi

done 
